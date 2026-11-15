-- ISOLATED TEST PROTOTYPE ONLY. Requires ledger/proof/gateway prototypes; not a migration or Cron activation.
begin;
create table conquest_internal.settlement_scopes (
  partition text primary key references conquest_internal.worlds(partition),
  catalog text not null references conquest_internal.catalogs(version),
  started_ms bigint not null check(started_ms>=0),
  first_ms bigint not null check(first_ms>started_ms),
  last_ms bigint,
  revision bigint not null default 0 check(revision>=0),
  road_count integer not null check(road_count>0),
  unique(partition,catalog),
  check((revision=0 and last_ms is null) or (revision>0 and last_ms>=first_ms))
);
create table conquest_internal.settlement_runs (
  partition text not null, cutoff_ms bigint not null, catalog text not null,
  window_start_ms bigint not null, revision bigint not null check(revision>0),
  completed_ms bigint not null check(completed_ms>=cutoff_ms),
  primary key(partition,cutoff_ms), unique(partition,revision), unique(partition,cutoff_ms,catalog),
  foreign key(partition,catalog) references conquest_internal.settlement_scopes(partition,catalog),
  check(window_start_ms<cutoff_ms)
);
create table conquest_internal.settlement_roads (
  partition text not null, cutoff_ms bigint not null, catalog text not null, road_id text not null,
  previous_owner text references conquest_internal.teams(id), owner text references conquest_internal.teams(id),
  scores jsonb not null check(jsonb_typeof(scores)='object'),
  reason text not null check(reason in ('CAPTURED','RETAINED','TAKEN_OVER','TIE_RETAINED','TIE_NEUTRAL','NO_CONTRIBUTIONS')),
  primary key(partition,cutoff_ms,road_id),
  foreign key(partition,cutoff_ms,catalog) references conquest_internal.settlement_runs(partition,cutoff_ms,catalog),
  foreign key(catalog,road_id) references conquest_internal.roads(catalog,id)
);
alter table conquest_internal.settlement_scopes add foreign key(partition,last_ms)
  references conquest_internal.settlement_runs(partition,cutoff_ms);

create function conquest_internal.latest_noon_ms(p_ms bigint) returns bigint
language sql stable strict set search_path='' as $$
  select (extract(epoch from ((date_trunc('day',local_time)+interval '12 hours'
    -case when extract(hour from local_time)<12 then interval '1 day' else interval '0 day' end)
    at time zone 'Asia/Seoul'))*1000)::bigint
  from (select to_timestamp(p_ms/1000.0) at time zone 'Asia/Seoul' local_time) t
$$;

-- Operator-only initialization. S is explicit; first settlement is the first KST noon STRICTLY after S.
create function conquest_internal.initialize_settlement(p_partition text,p_started_ms bigint) returns void
language plpgsql set search_path='' as $$
declare active_catalog text; saved conquest_internal.settlement_scopes%rowtype; total integer;
begin
  if p_started_ms is null or p_started_ms<0 then raise exception 'Invalid conquest start';end if;
  select catalog into active_catalog from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Unavailable conquest world';end if;
  select * into saved from conquest_internal.settlement_scopes where partition=p_partition for update;
  if found then
    if saved.catalog<>active_catalog or saved.started_ms<>p_started_ms then raise exception 'Conquest scope already initialized';end if;
    return;
  end if;
  select count(*) into total from conquest_internal.roads where catalog=active_catalog;
  if total=0 then raise exception 'Empty conquest catalog';end if;
  insert into conquest_internal.settlement_scopes(partition,catalog,started_ms,first_ms,road_count)
    values(p_partition,active_catalog,p_started_ms,conquest_internal.latest_noon_ms(p_started_ms)+86400000,total);
end $$;

-- Same world lock/order as contribution admission. No user/API role may supply a cutoff or run settlement.
-- p_test_now_ms is a trusted isolated fixture override; an operator schedule MUST omit it.
create function conquest_internal.settle_due(p_partition text,p_test_now_ms bigint default null,p_max_noons integer default 7)
returns jsonb language plpgsql set search_path='' as $$
declare active_catalog text; scope conquest_internal.settlement_scopes%rowtype; now_ms bigint;
  next_ms bigint; lower_ms bigint; processed integer:=0; inserted integer;
begin
  if p_max_noons is null or p_max_noons not between 1 and 31 then raise exception 'Invalid catch-up bound';end if;
  select catalog into active_catalog from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Unavailable conquest world';end if;
  -- Read clock only AFTER the submission/settlement serialization lock.
  now_ms:=coalesce(p_test_now_ms,conquest_internal.admission_now_ms());
  select * into scope from conquest_internal.settlement_scopes where partition=p_partition for update;
  if not found or scope.catalog<>active_catalog then raise exception 'Unavailable conquest settlement scope';end if;
  if now_ms is null or now_ms<scope.started_ms or now_ms<coalesce(scope.last_ms,scope.started_ms) then raise exception 'Invalid or backwards conquest clock';end if;
  if (select count(*) from conquest_internal.roads where catalog=scope.catalog)<>scope.road_count then
    raise exception 'Conquest catalog requires explicit migration';end if;
  if scope.last_ms is not null and (select count(*) from conquest_internal.settlement_roads
    where partition=p_partition and cutoff_ms=scope.last_ms)<>scope.road_count then
    raise exception 'Previous conquest settlement is incomplete';end if;
  next_ms:=coalesce(scope.last_ms+86400000,scope.first_ms);
  while next_ms<=now_ms and processed<p_max_noons loop
    lower_ms:=greatest(scope.started_ms,next_ms-129600000);
    insert into conquest_internal.settlement_runs values(p_partition,next_ms,scope.catalog,lower_ms,scope.revision+1,now_ms);
    with counts as (
      select road_id,team_id,count(*) points from conquest_internal.contributions
      where partition=p_partition and catalog=scope.catalog and occurred_ms>=lower_ms and occurred_ms<next_ms
      group by road_id,team_id
    ), ranked as (
      select *,max(points) over(partition by road_id) high from counts
    ), totals as (
      select road_id,jsonb_object_agg(team_id,points) scores,
        array_agg(team_id order by team_id) filter(where points=high) leaders from ranked group by road_id
    ), candidates as (
      select road.id road_id,old.owner previous_owner,coalesce(t.scores,'{}'::jsonb) scores,t.leaders,
        case when cardinality(t.leaders)=1 then t.leaders[1]
          when old.owner=any(t.leaders) then old.owner else null end owner
      from conquest_internal.roads road left join totals t on t.road_id=road.id
      left join conquest_internal.settlement_roads old on old.partition=p_partition and old.cutoff_ms=scope.last_ms and old.road_id=road.id
      where road.catalog=scope.catalog
    )
    insert into conquest_internal.settlement_roads
      select p_partition,next_ms,scope.catalog,road_id,previous_owner,owner,scores,
        case when leaders is null then 'NO_CONTRIBUTIONS'
          when cardinality(leaders)>1 then case when owner is null then 'TIE_NEUTRAL' else 'TIE_RETAINED' end
          when previous_owner is null then 'CAPTURED' when previous_owner=owner then 'RETAINED' else 'TAKEN_OVER' end
      from candidates;
    get diagnostics inserted=row_count;
    if inserted<>scope.road_count then raise exception 'Incomplete conquest settlement';end if;
    scope.last_ms:=next_ms;scope.revision:=scope.revision+1;
    update conquest_internal.settlement_scopes set last_ms=scope.last_ms,revision=scope.revision where partition=p_partition;
    processed:=processed+1;next_ms:=next_ms+86400000;
  end loop;
  return jsonb_build_object('processed',processed,'revision',scope.revision,'lastSettlementUtcMs',scope.last_ms,
    'nextSettlementUtcMs',next_ms,'hasMore',next_ms<=now_ms);
end $$;

-- A single statement snapshot, no settlement-on-read, no location proof or other users' contributions.
create function public.conquest_snapshot_v1(p_partition text,p_catalog text) returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare uid uuid:=auth.uid(); scope conquest_internal.settlement_scopes%rowtype;
  membership conquest_internal.memberships%rowtype; roads jsonb; own_count bigint; owned_count bigint; own_latest bigint;
begin
  if uid is null then raise exception 'Authentication required';end if;
  select s.* into scope from conquest_internal.settlement_scopes s join conquest_internal.worlds w on w.partition=s.partition
    where s.partition=p_partition and s.catalog=p_catalog and w.catalog=s.catalog;
  if not found then raise exception 'Unavailable conquest snapshot';end if;
  if (select count(*) from conquest_internal.roads where catalog=p_catalog)<>scope.road_count
    or (scope.last_ms is not null and (select count(*) from conquest_internal.settlement_roads
      where partition=p_partition and cutoff_ms=scope.last_ms)<>scope.road_count) then raise exception 'Incomplete conquest snapshot';end if;
  select * into membership from conquest_internal.memberships where user_id=uid and partition=p_partition;
  select coalesce(jsonb_agg(jsonb_build_object('roadId',r.id,'ownerTeamId',s.owner,'previousOwnerTeamId',s.previous_owner,
      'scores',coalesce(s.scores,'{}'::jsonb),'reason',coalesce(s.reason,'INITIAL')) order by r.id),'[]'::jsonb),
    count(*) filter(where s.owner=membership.team_id) into roads,owned_count
    from conquest_internal.roads r left join conquest_internal.settlement_roads s
      on s.partition=p_partition and s.cutoff_ms=scope.last_ms and s.road_id=r.id where r.catalog=p_catalog;
  select count(*),max(occurred_ms) into own_count,own_latest from conquest_internal.contributions
    where user_id=uid and partition=p_partition and catalog=p_catalog and occurred_ms>=scope.started_ms;
  return jsonb_build_object('protocol',1,'userId',uid::text,'partition',p_partition,'catalogVersion',p_catalog,
    'revision',scope.revision,'generatedUtcMs',conquest_internal.admission_now_ms(),'startedUtcMs',scope.started_ms,
    'lastSettlementUtcMs',scope.last_ms,'nextSettlementUtcMs',coalesce(scope.last_ms+86400000,scope.first_ms),
    'summary',jsonb_build_object('teamId',membership.team_id,'contributionCount',own_count,'ownedRoadCount',owned_count,
      'lastUpdatedUtcMs',case when membership.team_id is null then null else greatest(membership.selected_ms,own_latest,scope.last_ms) end),
    'roads',roads);
end $$;

alter table conquest_internal.settlement_scopes enable row level security;
alter table conquest_internal.settlement_runs enable row level security;
alter table conquest_internal.settlement_roads enable row level security;
revoke all on conquest_internal.settlement_scopes,conquest_internal.settlement_runs,conquest_internal.settlement_roads from public,anon,authenticated;
revoke all on function conquest_internal.latest_noon_ms(bigint) from public,anon,authenticated;
revoke all on function conquest_internal.initialize_settlement(text,bigint) from public,anon,authenticated;
revoke all on function conquest_internal.settle_due(text,bigint,integer) from public,anon,authenticated;
revoke all on function public.conquest_snapshot_v1(text,text) from public,anon,authenticated;
grant execute on function public.conquest_snapshot_v1(text,text) to authenticated;
commit;
