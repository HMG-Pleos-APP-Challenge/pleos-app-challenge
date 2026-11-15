-- ISOLATED TEST PROTOTYPE ONLY. Not a migration, public RPC or deployment script.
-- Trusted verifier input only: raw client completion events must never call these functions.
begin;
create schema conquest_internal;
revoke all on schema conquest_internal from public, anon, authenticated;

create table conquest_internal.teams (id text primary key check(length(id) between 1 and 100));
create table conquest_internal.catalogs (version text primary key);
create table conquest_internal.roads (
  catalog text references conquest_internal.catalogs(version),
  id text not null,
  primary key(catalog,id)
);
create table conquest_internal.worlds (
  partition text primary key check(partition in ('pleos','pleos-emulator')),
  catalog text not null references conquest_internal.catalogs(version)
);
create table conquest_internal.memberships (
  user_id uuid references auth.users(id),
  partition text references conquest_internal.worlds(partition),
  team_id text not null references conquest_internal.teams(id),
  selected_ms bigint not null check(selected_ms>=0),
  primary key(user_id,partition)
);
create table conquest_internal.receipts (
  user_id uuid references auth.users(id),
  partition text references conquest_internal.worlds(partition),
  event_id text check(length(event_id) between 1 and 200),
  payload jsonb not null,
  outcome jsonb not null,
  admitted_ms bigint not null check(admitted_ms>=0),
  primary key(user_id,partition,event_id)
);
create table conquest_internal.contributions (
  user_id uuid not null,
  partition text not null,
  event_id text not null,
  catalog text not null,
  road_id text not null,
  game_date date not null,
  afternoon boolean not null,
  occurred_ms bigint not null check(occurred_ms>=0),
  team_id text not null references conquest_internal.teams(id),
  primary key(user_id,partition,catalog,road_id,game_date,afternoon),
  unique(user_id,partition,event_id),
  foreign key(user_id,partition,event_id) references conquest_internal.receipts(user_id,partition,event_id),
  foreign key(catalog,road_id) references conquest_internal.roads(catalog,id)
);
create index conquest_window on conquest_internal.contributions(partition,catalog,occurred_ms,road_id,team_id);

-- All functions are SECURITY INVOKER, schema-private, and not executable by client roles.
-- Timestamps and user IDs are trusted test/verifier inputs, never request authorization.
create function conquest_internal.select_team(
  p_user uuid,p_partition text,p_team text,p_selected_ms bigint
) returns text language plpgsql set search_path='' as $$
declare old_team text;
begin
  if p_user is null or p_team is null or length(p_team) not between 1 and 100
    or p_selected_ms is null or p_selected_ms<0 then raise exception 'Invalid team input'; end if;
  perform 1 from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Invalid partition'; end if;
  select team_id into old_team from conquest_internal.memberships where user_id=p_user and partition=p_partition;
  if found then return case when old_team=p_team then 'UNCHANGED' else 'TEAM_LOCKED' end; end if;
  insert into conquest_internal.memberships values(p_user,p_partition,p_team,p_selected_ms);
  return 'SELECTED';
end $$;

create function conquest_internal.accept_verified(
  p_user uuid,p_partition text,p_catalog text,p_event text,p_road text,
  p_occurred_ms bigint,p_proof_hash text,p_detector text,p_test_admitted_ms bigint default null
) returns jsonb language plpgsql set search_path='' as $$
declare
  active_catalog text; canonical jsonb; prior conquest_internal.receipts%rowtype;
  membership conquest_internal.memberships%rowtype; result_code text; result jsonb;
  local_time timestamp; noon timestamp; cutoff_ms bigint; admitted_ms bigint;
begin
  if p_user is null or p_event is null or length(p_event) not between 1 and 200
    or p_catalog is null or p_road is null or length(p_road)=0
    or p_occurred_ms is null or p_occurred_ms<0 or (p_test_admitted_ms is not null and p_test_admitted_ms<0)
    or p_proof_hash is null or p_proof_hash !~ '^[a-f0-9]{64}$'
    or p_detector is null or length(p_detector) not between 1 and 100 then raise exception 'Invalid verified input'; end if;
  select catalog into active_catalog from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Invalid partition'; end if;
  -- Read wall clock AFTER the scope lock. Override is for isolated boundary tests only.
  -- A future client gateway must never expose this test argument.
  admitted_ms:=coalesce(p_test_admitted_ms,floor(extract(epoch from clock_timestamp())*1000)::bigint);
  canonical:=jsonb_build_object('catalog',p_catalog,'road',p_road,'occurred_ms',p_occurred_ms,
    'proof_hash',p_proof_hash,'detector',p_detector);
  select * into prior from conquest_internal.receipts
    where user_id=p_user and partition=p_partition and event_id=p_event;
  if found then
    if prior.payload<>canonical then return jsonb_build_object('eventId',p_event,'status','CONFLICT'); end if;
    return prior.outcome;
  end if;
  -- A future timestamp is retryable: do not permanently poison the event receipt.
  if p_occurred_ms>admitted_ms then return jsonb_build_object('eventId',p_event,'status','FUTURE'); end if;
  local_time:=to_timestamp(admitted_ms/1000.0) at time zone 'Asia/Seoul';
  noon:=date_trunc('day',local_time)+interval '12 hours';
  if local_time<noon then noon:=noon-interval '1 day'; end if;
  cutoff_ms:=(extract(epoch from (noon at time zone 'Asia/Seoul'))*1000)::bigint;
  select * into membership from conquest_internal.memberships where user_id=p_user and partition=p_partition;
  result_code:=case
    when active_catalog<>p_catalog then 'WRONG_CATALOG'
    when not exists(select 1 from conquest_internal.roads where catalog=p_catalog and id=p_road) then 'UNKNOWN_ROAD'
    when membership.team_id is null then 'NO_TEAM'
    when p_occurred_ms<membership.selected_ms then 'BEFORE_TEAM_SELECTION'
    when p_occurred_ms<cutoff_ms then 'LATE'
    else 'ACCEPTED' end;
  local_time:=to_timestamp(p_occurred_ms/1000.0) at time zone 'Asia/Seoul';
  if result_code='ACCEPTED' and exists(select 1 from conquest_internal.contributions
    where user_id=p_user and partition=p_partition and catalog=p_catalog and road_id=p_road
      and game_date=local_time::date and afternoon=(extract(hour from local_time)>=12)) then
    result_code:='DUPLICATE_HALF_DAY';
  end if;
  result:=jsonb_build_object('eventId',p_event,'status',result_code);
  insert into conquest_internal.receipts values(p_user,p_partition,p_event,canonical,result,admitted_ms);
  if result_code='ACCEPTED' then
    insert into conquest_internal.contributions values(p_user,p_partition,p_event,p_catalog,p_road,
      local_time::date,extract(hour from local_time)>=12,p_occurred_ms,membership.team_id);
  end if;
  return result;
end $$;

alter table conquest_internal.teams enable row level security;
alter table conquest_internal.catalogs enable row level security;
alter table conquest_internal.roads enable row level security;
alter table conquest_internal.worlds enable row level security;
alter table conquest_internal.memberships enable row level security;
alter table conquest_internal.receipts enable row level security;
alter table conquest_internal.contributions enable row level security;
revoke all on all tables in schema conquest_internal from public,anon,authenticated;
revoke all on all functions in schema conquest_internal from public,anon,authenticated;
commit;
