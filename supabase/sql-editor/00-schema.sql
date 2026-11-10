begin;
-- API v1. All geometry uses the SAME local metre projection as the bundled catalog.
create schema if not exists extensions;
create extension if not exists postgis with schema extensions;

create table public.exploration_targets (
  id text primary key, kind text not null check(kind in ('region','cell','landmark')),
  region_id text not null, dataset_version text not null,
  area extensions.geometry not null, roads extensions.geometry,
  dwell_ms bigint not null, max_gap_ms bigint not null, min_move double precision not null,
  xp integer not null check(xp>=0)
);
create index exploration_targets_area on public.exploration_targets using gist(area);
create table public.explorer_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '탐험가', created_at timestamptz not null default now()
);
create table public.exploration_grants (
  user_id uuid not null references auth.users(id) on delete cascade,
  partition text not null check(partition in ('pleos','pleos-emulator')),
  target_id text not null references public.exploration_targets(id),
  earned_ms bigint not null, xp integer not null,
  primary key(user_id,partition,target_id)
);
-- Only rolling candidates are retained; no permanent raw GPS history.
create table public.exploration_cursors (
  user_id uuid not null references auth.users(id) on delete cascade,
  partition text not null, last_ms bigint not null default -1,
  last_id text, last_point extensions.geometry,
  primary key(user_id,partition)
);
create table public.exploration_candidates (
  user_id uuid not null references auth.users(id) on delete cascade,
  partition text not null, target_id text not null references public.exploration_targets(id),
  first_ms bigint not null, last_ms bigint not null, first_point extensions.geometry not null,
  primary key(user_id,partition,target_id)
);
alter table public.exploration_targets enable row level security;
alter table public.explorer_profiles enable row level security;
alter table public.exploration_grants enable row level security;
alter table public.exploration_cursors enable row level security;
alter table public.exploration_candidates enable row level security;
revoke all on public.exploration_targets, public.explorer_profiles, public.exploration_grants,
  public.exploration_cursors, public.exploration_candidates from anon, authenticated;
grant select on public.exploration_targets, public.explorer_profiles, public.exploration_grants to authenticated;
create policy catalog_read on public.exploration_targets for select to authenticated using(true);
create policy profile_own on public.explorer_profiles for select to authenticated using(user_id=(select auth.uid()));
create policy grants_own on public.exploration_grants for select to authenticated using(user_id=(select auth.uid()));

create function public.sync_exploration_v1(p_partition text, p_samples jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  uid uuid := auth.uid(); s jsonb; tm bigint; eid text; lat double precision; lon double precision;
  acc double precision; pt extensions.geometry; prev public.exploration_cursors%rowtype;
  target public.exploration_targets%rowtype; candidate public.exploration_candidates%rowtype;
  eligible text[]; result jsonb;
  sx constant double precision := pi()/180*6371008.8*cos(37.2*pi()/180);
  sy constant double precision := pi()/180*6371008.8;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_partition is null or p_partition not in ('pleos','pleos-emulator') then raise exception 'Invalid partition'; end if;
  if p_samples is null or jsonb_typeof(p_samples)<>'array' or jsonb_array_length(p_samples)>100 then raise exception 'Invalid batch'; end if;
  insert into public.explorer_profiles(user_id) values(uid) on conflict do nothing;
  insert into public.exploration_cursors(user_id,partition) values(uid,p_partition) on conflict do nothing;
  -- Serializes competing sync calls for this user/partition; grants + XP commit atomically.
  select * into prev from public.exploration_cursors where user_id=uid and partition=p_partition for update;
  for s in select value from jsonb_array_elements(p_samples) loop
    tm := (s->>'time')::bigint; eid := s->>'id';
    if tm is null or eid is null or length(eid) not between 1 and 200 then raise exception 'Malformed sample'; end if;
    if tm<=prev.last_ms or eid=prev.last_id then continue; end if;
    if tm<extract(epoch from now()-interval '30 days')*1000 or tm>extract(epoch from now()+interval '5 minutes')*1000 then
      raise exception 'Sample outside supported offline window';
    end if;
    lat:=(s->>'lat')::double precision;lon:=(s->>'lon')::double precision;acc:=(s->>'accuracy')::double precision;
    eligible := array[]::text[];
    pt:=null;
    if lat between -90 and 90 and lon between -180 and 180 and acc between 0 and 25 then
      pt:=extensions.st_makepoint((lon-126.8)*sx,(lat-37.2)*sy);
      if prev.last_point is not null and tm-prev.last_ms<=30000 and
         extensions.st_distance(pt,prev.last_point)*1000/(tm-prev.last_ms)>60 then pt:=null; end if;
    end if;
    if pt is not null then
      select coalesce(array_agg(t.id),array[]::text[]) into eligible from public.exploration_targets t
      where extensions.st_contains(t.area,pt)
        and extensions.st_distance(extensions.st_boundary(t.area),pt)>acc
        and (t.kind<>'cell' or extensions.st_dwithin(t.roads,pt,30-acc))
        and exists(select 1 from public.exploration_targets r where r.id=t.region_id
          and extensions.st_contains(r.area,pt) and extensions.st_distance(extensions.st_boundary(r.area),pt)>acc);
      -- Region discovery gates road and landmark rewards.
      for target in select * from public.exploration_targets where id=any(eligible)
          order by case kind when 'region' then 0 else 1 end, id loop
        select * into candidate from public.exploration_candidates
          where user_id=uid and partition=p_partition and target_id=target.id;
        if not found or tm-candidate.last_ms>target.max_gap_ms then
          insert into public.exploration_candidates values(uid,p_partition,target.id,tm,tm,pt)
            on conflict(user_id,partition,target_id) do update set first_ms=tm,last_ms=tm,first_point=pt;
        else
          update public.exploration_candidates set last_ms=tm where user_id=uid and partition=p_partition and target_id=target.id;
          if tm-candidate.first_ms>=target.dwell_ms and extensions.st_distance(candidate.first_point,pt)>=target.min_move
             and (target.kind='region' or exists(select 1 from public.exploration_grants
               where user_id=uid and partition=p_partition and target_id=target.region_id)) then
            insert into public.exploration_grants values(uid,p_partition,target.id,tm,target.xp) on conflict do nothing;
          end if;
        end if;
      end loop;
    end if;
    delete from public.exploration_candidates where user_id=uid and partition=p_partition and not(target_id=any(eligible));
    -- Invalid accuracy breaks continuity; timestamp watermark still advances.
    prev.last_ms:=tm;prev.last_id:=eid;prev.last_point:=pt;
  end loop;
  update public.exploration_cursors set last_ms=prev.last_ms,last_id=prev.last_id,last_point=prev.last_point
    where user_id=uid and partition=p_partition;
  select jsonb_build_object('xp',coalesce(sum(g.xp),0),'grants',coalesce(jsonb_agg(jsonb_build_object(
    'kind',t.kind,'target_id',g.target_id,'earned_ms',g.earned_ms) order by g.target_id),'[]'::jsonb))
    into result from public.exploration_grants g join public.exploration_targets t on t.id=g.target_id
    where g.user_id=uid and g.partition=p_partition;
  return result;
end $$;
revoke all on function public.sync_exploration_v1(text,jsonb) from public, anon;
grant execute on function public.sync_exploration_v1(text,jsonb) to authenticated;

-- Reserved structure only. No ownership mutation API until shared rules are approved.
-- A road_segment_id is NOT a road cell ID. No automatic cell->conquest conversion.
create table public.conquest_road_catalog (
  road_segment_id text primary key,
  dataset_version text not null,
  region_id text not null,
  path extensions.geometry not null
);
create table public.conquest_ownership (
  road_segment_id text primary key references public.conquest_road_catalog(road_segment_id),
  owner_user_id uuid references auth.users(id) on delete set null,
  revision bigint not null default 0,
  changed_at timestamptz not null default now()
);
alter table public.conquest_road_catalog enable row level security;
alter table public.conquest_ownership enable row level security;
revoke all on public.conquest_road_catalog, public.conquest_ownership from anon,authenticated;
-- Intentionally no client access, seed or claim function before the collaborator contract.

-- Upgrade preparation. Old grants/targets are retained for audit; active totals are version-filtered.
create table if not exists public.exploration_grid_config (
  singleton boolean primary key default true check(singleton), version text not null
);
alter table public.exploration_grid_config enable row level security;
revoke all on public.exploration_grid_config from public, anon, authenticated;
insert into public.exploration_grid_config values(true,'osm-geofabrik-20261108-namyang-grid100-v1') on conflict do nothing;

create or replace function public.activate_grid500_v2() returns void
language plpgsql security definer set search_path='' as $$
begin
  if (select count(*) from public.exploration_targets where kind='cell' and dataset_version='osm-geofabrik-20261108-namyang-grid500-v2')<>243 then
    raise exception 'Load all 243 grid500 cells before activation';
  end if;
  insert into public.exploration_grants(user_id,partition,target_id,earned_ms,xp)
    select g.user_id,g.partition,n.id,min(g.earned_ms),10
    from public.exploration_grants g join public.exploration_targets o on o.id=g.target_id
    join public.exploration_targets n on n.id='osm-geofabrik-20261108-namyang-grid500-v2:'
      ||floor(split_part(o.id,':',2)::numeric/5)::int::text||':'||floor(split_part(o.id,':',3)::numeric/5)::int::text
    where o.kind='cell' and o.dataset_version='osm-geofabrik-20261108-namyang-grid100-v1'
    group by g.user_id,g.partition,n.id
    on conflict(user_id,partition,target_id) do update set earned_ms=least(exploration_grants.earned_ms,excluded.earned_ms),xp=10;
  delete from public.exploration_candidates c using public.exploration_targets t
    where c.target_id=t.id and t.kind='cell' and t.dataset_version='osm-geofabrik-20261108-namyang-grid100-v1';
  update public.exploration_grid_config set version='osm-geofabrik-20261108-namyang-grid500-v2' where singleton;
end $$;
revoke all on function public.activate_grid500_v2() from public,anon,authenticated;

create or replace function public.sync_exploration_v1(p_partition text, p_samples jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare
  uid uuid := auth.uid(); s jsonb; tm bigint; eid text; lat double precision; lon double precision;
  acc double precision; pt extensions.geometry; prev public.exploration_cursors%rowtype;
  target public.exploration_targets%rowtype; candidate public.exploration_candidates%rowtype;
  eligible text[]; result jsonb;
  sx constant double precision := pi()/180*6371008.8*cos(37.2*pi()/180);
  sy constant double precision := pi()/180*6371008.8;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_partition is null or p_partition not in ('pleos','pleos-emulator') then raise exception 'Invalid partition'; end if;
  if p_samples is null or jsonb_typeof(p_samples)<>'array' or jsonb_array_length(p_samples)>100 then raise exception 'Invalid batch'; end if;
  insert into public.explorer_profiles(user_id) values(uid) on conflict do nothing;
  insert into public.exploration_cursors(user_id,partition) values(uid,p_partition) on conflict do nothing;
  -- Serializes competing sync calls for this user/partition; grants + XP commit atomically.
  select * into prev from public.exploration_cursors where user_id=uid and partition=p_partition for update;
  for s in select value from jsonb_array_elements(p_samples) loop
    tm := (s->>'time')::bigint; eid := s->>'id';
    if tm is null or eid is null or length(eid) not between 1 and 200 then raise exception 'Malformed sample'; end if;
    if tm<=prev.last_ms or eid=prev.last_id then continue; end if;
    if tm<extract(epoch from now()-interval '30 days')*1000 or tm>extract(epoch from now()+interval '5 minutes')*1000 then
      raise exception 'Sample outside supported offline window';
    end if;
    lat:=(s->>'lat')::double precision;lon:=(s->>'lon')::double precision;acc:=(s->>'accuracy')::double precision;
    eligible := array[]::text[];
    pt:=null;
    if lat between -90 and 90 and lon between -180 and 180 and acc between 0 and 25 then
      pt:=extensions.st_makepoint((lon-126.8)*sx,(lat-37.2)*sy);
      if prev.last_point is not null and tm-prev.last_ms<=30000 and
         extensions.st_distance(pt,prev.last_point)*1000/(tm-prev.last_ms)>60 then pt:=null; end if;
    end if;
    if pt is not null then
      select coalesce(array_agg(t.id),array[]::text[]) into eligible from public.exploration_targets t
      where (t.kind<>'cell' or t.dataset_version=(select version from public.exploration_grid_config where singleton))
        and extensions.st_contains(t.area,pt)
        and extensions.st_distance(extensions.st_boundary(t.area),pt)>acc
        and (t.kind<>'cell' or extensions.st_dwithin(t.roads,pt,30-acc))
        and exists(select 1 from public.exploration_targets r where r.id=t.region_id
          and extensions.st_contains(r.area,pt) and extensions.st_distance(extensions.st_boundary(r.area),pt)>acc);
      -- Region discovery gates road and landmark rewards.
      for target in select * from public.exploration_targets where id=any(eligible)
          order by case kind when 'region' then 0 else 1 end, id loop
        select * into candidate from public.exploration_candidates
          where user_id=uid and partition=p_partition and target_id=target.id;
        if not found or tm-candidate.last_ms>target.max_gap_ms then
          insert into public.exploration_candidates values(uid,p_partition,target.id,tm,tm,pt)
            on conflict(user_id,partition,target_id) do update set first_ms=tm,last_ms=tm,first_point=pt;
        else
          update public.exploration_candidates set last_ms=tm where user_id=uid and partition=p_partition and target_id=target.id;
          if tm-candidate.first_ms>=target.dwell_ms and extensions.st_distance(candidate.first_point,pt)>=target.min_move
             and (target.kind='region' or exists(select 1 from public.exploration_grants
               where user_id=uid and partition=p_partition and target_id=target.region_id)) then
            insert into public.exploration_grants values(uid,p_partition,target.id,tm,target.xp) on conflict do nothing;
          end if;
        end if;
      end loop;
    end if;
    delete from public.exploration_candidates where user_id=uid and partition=p_partition and not(target_id=any(eligible));
    -- Invalid accuracy breaks continuity; timestamp watermark still advances.
    prev.last_ms:=tm;prev.last_id:=eid;prev.last_point:=pt;
  end loop;
  update public.exploration_cursors set last_ms=prev.last_ms,last_id=prev.last_id,last_point=prev.last_point
    where user_id=uid and partition=p_partition;
  select jsonb_build_object('catalog_version',(select version from public.exploration_grid_config where singleton),'xp',coalesce(sum(g.xp),0),'grants',coalesce(jsonb_agg(jsonb_build_object(
    'kind',t.kind,'target_id',g.target_id,'earned_ms',g.earned_ms) order by g.target_id),'[]'::jsonb))
    into result from public.exploration_grants g join public.exploration_targets t on t.id=g.target_id
    where g.user_id=uid and g.partition=p_partition
      and (t.kind<>'cell' or t.dataset_version=(select version from public.exploration_grid_config where singleton));
  return result;
end $$;
revoke all on function public.sync_exploration_v1(text,jsonb) from public, anon;
grant execute on function public.sync_exploration_v1(text,jsonb) to authenticated;

commit;
