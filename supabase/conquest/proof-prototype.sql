-- ISOLATED PROTOTYPE ONLY. No client RPC, migration or production deployment.
-- Request coordinates are transient variables. Only public catalog geometry is stored here.
begin;
create table conquest_internal.road_geometry (
  catalog text not null, road_id text not null, ordinal integer not null check(ordinal>=0),
  path extensions.geometry(LineString,4326) not null,
  primary key(catalog,road_id), unique(catalog,ordinal),
  foreign key(catalog,road_id) references conquest_internal.roads(catalog,id),
  check(extensions.st_npoints(path)>=2 and not extensions.st_isempty(path))
);
create index conquest_geometry on conquest_internal.road_geometry using gist(path);
alter table conquest_internal.road_geometry enable row level security;
revoke all on conquest_internal.road_geometry from public,anon,authenticated;

-- Same road-local equirectangular projection and 30m endpoint extensions as DirectedRoad.
create function conquest_internal.locate(p_path extensions.geometry,p_lat double precision,p_lon double precision)
returns table(distance_m double precision,progress_m double precision,length_m double precision)
language plpgsql immutable set search_path='' as $$
declare
  origin extensions.geometry:=extensions.st_startpoint(p_path);
  sx double precision:=pi()/180*6371008.8*cos(radians(extensions.st_y(origin)));
  sy double precision:=pi()/180*6371008.8;
  px double precision:=(p_lon-extensions.st_x(origin))*sx;
  py double precision:=(p_lat-extensions.st_y(origin))*sy;
  a extensions.geometry; b extensions.geometry; ax double precision; ay double precision;
  dx double precision; dy double precision; len double precision; raw double precision; t double precision;
  d double precision; travelled double precision:=0; i integer; n integer:=extensions.st_npoints(p_path);
begin
  distance_m:='Infinity'::double precision; progress_m:=0;
  for i in 1..n-1 loop
    a:=extensions.st_pointn(p_path,i); b:=extensions.st_pointn(p_path,i+1);
    ax:=(extensions.st_x(a)-extensions.st_x(origin))*sx;
    ay:=(extensions.st_y(a)-extensions.st_y(origin))*sy;
    dx:=(extensions.st_x(b)-extensions.st_x(a))*sx;
    dy:=(extensions.st_y(b)-extensions.st_y(a))*sy;
    len:=sqrt(dx*dx+dy*dy);
    if len<=0 then raise exception 'Degenerate catalog geometry'; end if;
    raw:=((px-ax)*dx+(py-ay)*dy)/(len*len);
    t:=case when i=1 and raw<0 then greatest(raw,-30/len)
      when i=n-1 and raw>1 then least(raw,1+30/len) else greatest(0,least(raw,1)) end;
    d:=sqrt(power(px-ax-t*dx,2)+power(py-ay-t*dy,2));
    if d<distance_m then distance_m:=d;progress_m:=travelled+t*len;end if;
    travelled:=travelled+len;
  end loop;
  if travelled<50 then raise exception 'Catalog road shorter than 50m'; end if;
  length_m:=travelled;return next;
end $$;

-- Replay only. Defaults mirror DirectedRoadDetector; this version has no persistent trip cursor.
create function conquest_internal.replay_proof(p_catalog text,p_samples jsonb)
returns jsonb language plpgsql stable set search_path='' as $$
declare
  sample jsonb; previous jsonb; trips jsonb:='{}'; next_trips jsonb; trip jsonb;
  completions jsonb:='[]'; completed text[]; road conquest_internal.road_geometry%rowtype;
  lat double precision; lon double precision; acc double precision; tm bigint; eid text;
  old_lat double precision; old_lon double precision; old_tm bigint; step_sx double precision;
  sy constant double precision:=pi()/180*6371008.8; step_m double precision;
  pos record; sx double precision; exit_m double precision; observations integer;
  reached boolean; max_progress double precision;
begin
  -- Technical bound for isolated tests, not an approved device queue/proof size policy.
  if p_samples is null or jsonb_typeof(p_samples)<>'array' or jsonb_array_length(p_samples) not between 3 and 2048
    then raise exception 'Invalid proof batch';end if;
  if (select count(*) from conquest_internal.road_geometry where catalog=p_catalog)=0 or
    (select count(*) from conquest_internal.road_geometry where catalog=p_catalog)<>
    (select count(*) from conquest_internal.roads where catalog=p_catalog)
    then raise exception 'Incomplete catalog geometry';end if;
  select pi()/180*6371008.8*cos(radians(extensions.st_y(extensions.st_startpoint(path)))) into step_sx
    from conquest_internal.road_geometry where catalog=p_catalog order by ordinal limit 1;
  for sample in select value from jsonb_array_elements(p_samples) loop
    if previous=sample then continue;end if;
    if jsonb_typeof(sample)<>'object' or jsonb_typeof(sample->'id') is distinct from 'string'
      or jsonb_typeof(sample->'time') is distinct from 'number'
      or jsonb_typeof(sample->'lat') is distinct from 'number'
      or jsonb_typeof(sample->'lon') is distinct from 'number'
      or jsonb_typeof(sample->'accuracy') is distinct from 'number'
      then trips:='{}';continue;end if;
    eid:=sample->>'id';lat:=(sample->>'lat')::double precision;lon:=(sample->>'lon')::double precision;
    acc:=(sample->>'accuracy')::real::double precision;
    if (sample->>'time')::numeric<>trunc((sample->>'time')::numeric) then trips:='{}';continue;end if;
    tm:=(sample->>'time')::bigint;
    if length(eid) not between 1 and 200 or length(btrim(eid))=0 or tm<0 or lat not between -80 and 80 or lon not between -180 and 180
      or acc not between 0 and 10 or (previous is not null and tm<=old_tm)
      then trips:='{}';continue;end if;
    if previous is not null then
      step_m:=sqrt(power((lon-old_lon)*step_sx,2)+power((lat-old_lat)*sy,2));
    end if;
    previous:=sample;
    if old_tm is not null and (tm-old_tm>15000 or step_m>60 or step_m>40*(tm-old_tm)/1000.0) then
      trips:='{}';old_tm:=tm;old_lat:=lat;old_lon:=lon;continue;
    end if;
    old_tm:=tm;old_lat:=lat;old_lon:=lon;
    next_trips:='{}';completed:=array[]::text[];
    for road in select * from conquest_internal.road_geometry g where g.catalog=p_catalog and
      (trips ? g.road_id or extensions.st_intersects(extensions.st_expand(g.path,0.004),
        extensions.st_setsrid(extensions.st_makepoint(lon,lat),4326))) order by ordinal loop
      select * into pos from conquest_internal.locate(road.path,lat,lon);
      trip:=trips->road.road_id;
      sx:=pi()/180*6371008.8*cos(radians(extensions.st_y(extensions.st_startpoint(road.path))));
      exit_m:=sqrt(power((lon-extensions.st_x(extensions.st_endpoint(road.path)))*sx,2)+
        power((lat-extensions.st_y(extensions.st_endpoint(road.path)))*sy,2));
      observations:=coalesce((trip->>'observations')::integer,0);
      reached:=coalesce((trip->>'exit')::boolean,false);
      if reached and observations>=3 and exit_m>12+acc and pos.progress_m>=pos.length_m-8 then
        completed:=array_append(completed,road.road_id);continue;
      end if;
      if pos.distance_m+acc>20 then continue;end if;
      if trip is null then
        if pos.progress_m>=-12+acc and pos.progress_m<=12-acc then
          next_trips:=next_trips||jsonb_build_object(road.road_id,jsonb_build_object('max',pos.progress_m,'observations',1,'exit',false));
        end if;
      elsif pos.progress_m>=(trip->>'max')::double precision-8 then
        max_progress:=greatest((trip->>'max')::double precision,pos.progress_m);
        if pos.progress_m>=pos.length_m+greatest(2,acc) and observations>=3 then
          completed:=array_append(completed,road.road_id);
        else
          reached:=reached or (pos.progress_m>=pos.length_m-acc and exit_m<=12-acc);
          next_trips:=next_trips||jsonb_build_object(road.road_id,
            jsonb_build_object('max',max_progress,'observations',observations+1,'exit',reached));
        end if;
      end if;
    end loop;
    trips:=next_trips;
    if cardinality(completed)=1 then completions:=completions||jsonb_build_array(
      jsonb_build_object('roadId',completed[1],'eventId',eid||':conquest:'||completed[1],'occurredMs',tm));end if;
  end loop;
  return completions;
end $$;

-- Transient proof -> trusted ledger. Still private: no client UID/time/proof claims are trusted by an API.
create function conquest_internal.submit_proof(
  p_user uuid,p_partition text,p_catalog text,p_event text,p_road text,p_occurred_ms bigint,
  p_samples jsonb,p_test_admitted_ms bigint default null
) returns jsonb language plpgsql set search_path='' as $$
declare proof_hash text; events jsonb; matched jsonb; result jsonb;
  detector constant text:='directed-road-v1';
begin
  if p_samples is null or jsonb_typeof(p_samples)<>'array' or jsonb_array_length(p_samples) not between 3 and 2048
    then raise exception 'Invalid proof batch';end if;
  proof_hash:=encode(sha256(convert_to(p_samples::text,'UTF8')),'hex');
  -- Resolve prior immutable receipts before revalidation or the new-event deadline.
  if exists(select 1 from conquest_internal.receipts where user_id=p_user and partition=p_partition and event_id=p_event) then
    return conquest_internal.accept_verified(p_user,p_partition,p_catalog,p_event,p_road,p_occurred_ms,
      proof_hash,detector,p_test_admitted_ms);
  end if;
  events:=conquest_internal.replay_proof(p_catalog,p_samples);
  matched:=events->0;
  if jsonb_array_length(events)<>1 or matched->>'roadId' is distinct from p_road
    or matched->>'eventId' is distinct from p_event or (matched->>'occurredMs')::bigint is distinct from p_occurred_ms
    or (p_samples->-1->>'time')::bigint is distinct from p_occurred_ms then
    -- A final rejection is immutable too. Recheck after the shared scope lock to handle a competing submit.
    perform 1 from conquest_internal.worlds where partition=p_partition for update;
    if not found then raise exception 'Invalid partition';end if;
    if exists(select 1 from conquest_internal.receipts where user_id=p_user and partition=p_partition and event_id=p_event) then
      return conquest_internal.accept_verified(p_user,p_partition,p_catalog,p_event,p_road,p_occurred_ms,
        proof_hash,detector,p_test_admitted_ms);
    end if;
    result:=jsonb_build_object('eventId',p_event,'status','INVALID_PROOF');
    insert into conquest_internal.receipts values(p_user,p_partition,p_event,
      jsonb_build_object('catalog',p_catalog,'road',p_road,'occurred_ms',p_occurred_ms,
        'proof_hash',proof_hash,'detector',detector),result,
      coalesce(p_test_admitted_ms,floor(extract(epoch from clock_timestamp())*1000)::bigint));
    return result;
  end if;
  return conquest_internal.accept_verified(p_user,p_partition,p_catalog,p_event,p_road,p_occurred_ms,
    proof_hash,detector,p_test_admitted_ms);
end $$;

revoke all on all functions in schema conquest_internal from public,anon,authenticated;
commit;
