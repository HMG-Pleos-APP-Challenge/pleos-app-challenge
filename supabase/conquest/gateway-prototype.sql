-- ISOLATED API PROTOTYPE. Not a migration or production deployment.
-- Requires ledger-prototype.sql and proof-prototype.sql in a fresh test database.
begin;
create function conquest_internal.admission_now_ms() returns bigint
language sql volatile set search_path='' as $$ select floor(extract(epoch from clock_timestamp())*1000)::bigint $$;
create table conquest_internal.gateway_receipts (
  user_id uuid not null, partition text not null, event_id text not null,
  request_hash text not null check(request_hash ~ '^[a-f0-9]{64}$'),
  primary key(user_id,partition,event_id),
  foreign key(user_id,partition,event_id) references conquest_internal.receipts(user_id,partition,event_id)
);
alter table conquest_internal.gateway_receipts enable row level security;
revoke all on conquest_internal.gateway_receipts from public,anon,authenticated;
revoke all on function conquest_internal.admission_now_ms() from public,anon,authenticated;

-- Narrow privileged gateways only: derive UID from verified JWT, not request arguments.
create function public.conquest_select_team_v1(p_partition text,p_catalog text,p_team text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare uid uuid:=auth.uid(); active_catalog text; result text; membership conquest_internal.memberships%rowtype;
begin
  if uid is null then raise exception 'Authentication required';end if;
  select catalog into active_catalog from conquest_internal.worlds where partition=p_partition for update;
  if not found or active_catalog is distinct from p_catalog then raise exception 'Unavailable conquest catalog';end if;
  result:=conquest_internal.select_team(uid,p_partition,p_team,conquest_internal.admission_now_ms());
  select * into membership from conquest_internal.memberships where user_id=uid and partition=p_partition;
  return jsonb_build_object('protocol',1,'userId',uid::text,'partition',p_partition,'catalogVersion',p_catalog,
    'status',result,'teamId',membership.team_id,'selectedUtcMs',membership.selected_ms);
end $$;

-- p_events is an array of immutable UTF-8 JSON strings. Hash the exact strings for queue ACK correlation.
create function public.conquest_submit_v1(p_partition text,p_catalog text,p_events jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  uid uuid:=auth.uid(); active_catalog text; admitted bigint; item jsonb; body jsonb; raw text;
  bodies jsonb:='[]'; ids text[]:=array[]::text[]; results jsonb:='[]'; v_event_id text; v_request_hash text;
  saved_hash text; result jsonb; status text; row record;
begin
  if uid is null then raise exception 'Authentication required';end if;
  -- Provisional isolated-test bounds, to be measured/reviewed before release.
  if p_events is null or jsonb_typeof(p_events)<>'array' or jsonb_array_length(p_events) not between 1 and 20
    or octet_length(p_events::text)>1048576 then raise exception 'Invalid conquest batch';end if;
  -- Validate the entire envelope before writing any item. Unknown owner/clock fields are rejected.
  for item in select value from jsonb_array_elements(p_events) loop
    if jsonb_typeof(item)<>'string' then raise exception 'Expected immutable event JSON';end if;
    raw:=item#>>'{}';
    if octet_length(raw)>262144 then raise exception 'Conquest event too large';end if;
    begin body:=raw::jsonb; exception when others then raise exception 'Malformed conquest event';end;
    if jsonb_typeof(body)<>'object' or
      (select count(*) from jsonb_object_keys(body))<>4 or
      not (body ?& array['eventId','roadId','occurredMs','samples']) or
      jsonb_typeof(body->'eventId') is distinct from 'string' or
      jsonb_typeof(body->'roadId') is distinct from 'string' or
      jsonb_typeof(body->'occurredMs') is distinct from 'number' or
      (body->>'occurredMs') !~ '^(0|[1-9][0-9]*)$' or
      jsonb_typeof(body->'samples') is distinct from 'array' then raise exception 'Invalid conquest event fields';end if;
    v_event_id:=body->>'eventId';
    if length(v_event_id) not between 1 and 200 or length(btrim(v_event_id))=0 or length(btrim(body->>'roadId'))=0
      or jsonb_array_length(body->'samples') not between 3 and 2048 then raise exception 'Invalid conquest event bounds';end if;
    perform (body->>'occurredMs')::bigint;
    if v_event_id=any(ids) then raise exception 'Duplicate event in batch';end if;
    ids:=array_append(ids,v_event_id);
    bodies:=bodies||jsonb_build_array(body);
  end loop;
  select catalog into active_catalog from conquest_internal.worlds where partition=p_partition for update;
  -- Keep immutable retries readable after a catalog change. New old-version events are rejected by the ledger.
  if not found or not exists(select 1 from conquest_internal.catalogs where version=p_catalog)
    then raise exception 'Unavailable conquest catalog';end if;
  admitted:=conquest_internal.admission_now_ms();
  for row in select value,ordinality from jsonb_array_elements(p_events) with ordinality loop
    raw:=row.value#>>'{}';body:=bodies->(row.ordinality::integer-1);v_event_id:=body->>'eventId';
    v_request_hash:=encode(sha256(convert_to(raw,'UTF8')),'hex');
    select g.request_hash into saved_hash from conquest_internal.gateway_receipts g
      where g.user_id=uid and g.partition=p_partition and g.event_id=v_event_id;
    if found and saved_hash<>v_request_hash then
      result:=jsonb_build_object('status','CONFLICT');
    else
      if not found and exists(select 1 from conquest_internal.receipts
        where user_id=uid and partition=p_partition and event_id=v_event_id) then
        raise exception 'Receipt requires explicit gateway migration';
      end if;
      result:=conquest_internal.submit_proof(uid,p_partition,p_catalog,v_event_id,body->>'roadId',
        (body->>'occurredMs')::bigint,body->'samples',admitted);
      if (result->>'status') not in ('FUTURE','CONFLICT') then
        insert into conquest_internal.gateway_receipts values(uid,p_partition,v_event_id,v_request_hash) on conflict do nothing;
      end if;
    end if;
    status:=result->>'status';
    results:=results||jsonb_build_array(jsonb_build_object('eventId',v_event_id,'roadId',body->>'roadId',
      'occurredMs',(body->>'occurredMs')::bigint,'requestHash',v_request_hash,'status',status,
      'acknowledge',status not in ('FUTURE','CONFLICT'),'retryable',status='FUTURE'));
  end loop;
  return jsonb_build_object('protocol',1,'userId',uid::text,'partition',p_partition,'catalogVersion',p_catalog,
    'receivedUtcMs',admitted,'results',results);
end $$;

revoke all on function public.conquest_select_team_v1(text,text,text) from public,anon,authenticated;
revoke all on function public.conquest_submit_v1(text,text,jsonb) from public,anon,authenticated;
grant execute on function public.conquest_select_team_v1(text,text,text) to authenticated;
grant execute on function public.conquest_submit_v1(text,text,jsonb) to authenticated;
commit;
