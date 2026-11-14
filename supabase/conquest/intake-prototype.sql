-- Isolated deployment prototype, applied AFTER gateway and settlement. Not an operating migration.
-- Initially closed. Only the server operator can read/change state; clients receive HTTP 503.
begin;
create table conquest_internal.intake_control (
  partition text primary key check(partition in ('pleos','pleos-emulator')),
  paused boolean not null default true,
  revision bigint not null default 0 check(revision>=0),
  changed_ms bigint not null check(changed_ms>=0),
  reason text not null check(length(btrim(reason)) between 1 and 200),
  changed_by text not null
);
create table conquest_internal.intake_changes (
  partition text references conquest_internal.intake_control(partition),
  revision bigint not null check(revision>=0), paused boolean not null,
  changed_ms bigint not null check(changed_ms>=0), reason text not null,
  changed_by text not null, primary key(partition,revision)
);
insert into conquest_internal.intake_control(partition,changed_ms,reason,changed_by)
  select partition,floor(extract(epoch from clock_timestamp())*1000)::bigint,'Initially paused',session_user
  from (values('pleos'),('pleos-emulator')) partitions(partition);
insert into conquest_internal.intake_changes select partition,revision,paused,changed_ms,reason,changed_by
  from conquest_internal.intake_control;
alter table conquest_internal.intake_control enable row level security;
alter table conquest_internal.intake_changes enable row level security;
revoke all on conquest_internal.intake_control,conquest_internal.intake_changes from public,anon,authenticated;

create function conquest_internal.intake_status(p_partition text) returns jsonb
language plpgsql stable set search_path='' as $$
declare state conquest_internal.intake_control%rowtype;
begin
  select * into state from conquest_internal.intake_control where partition=p_partition;
  if not found then raise exception 'Unavailable conquest intake control';end if;
  return jsonb_build_object('partition',state.partition,'paused',state.paused,'revision',state.revision,
    'changedUtcMs',state.changed_ms,'reason',state.reason,'databaseRole',state.changed_by,
    'worldConfigured',exists(select 1 from conquest_internal.worlds where partition=p_partition));
end $$;

-- INVOKER, no client grant. Compare-and-set prevents a stale operator from undoing a newer pause.
create function conquest_internal.set_intake(p_partition text,p_paused boolean,p_expected_revision bigint,p_reason text)
returns jsonb language plpgsql set search_path='' as $$
declare state conquest_internal.intake_control%rowtype; changed bigint;
begin
  if p_paused is null or p_expected_revision is null or p_expected_revision<0 or p_reason is null
    or length(btrim(p_reason)) not between 1 and 200 then raise exception 'Invalid intake operation';end if;
  perform 1 from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Unavailable conquest world';end if;
  select * into state from conquest_internal.intake_control where partition=p_partition for update;
  if not found then raise exception 'Unavailable conquest intake control';end if;
  if state.revision<>p_expected_revision then raise exception 'Intake revision changed; read current state';end if;
  if state.paused=p_paused then return conquest_internal.intake_status(p_partition);end if;
  changed:=floor(extract(epoch from clock_timestamp())*1000)::bigint;
  update conquest_internal.intake_control set paused=p_paused,revision=state.revision+1,
    changed_ms=changed,reason=btrim(p_reason),changed_by=session_user where partition=p_partition;
  insert into conquest_internal.intake_changes values(p_partition,state.revision+1,p_paused,changed,btrim(p_reason),session_user);
  return conquest_internal.intake_status(p_partition);
end $$;
revoke all on function conquest_internal.intake_status(text) from public,anon,authenticated;
revoke all on function conquest_internal.set_intake(text,boolean,bigint,text) from public,anon,authenticated;

-- Retain the exact verified gateway implementation behind a private boundary, without copying its body.
alter function public.conquest_submit_v1(text,text,jsonb) set schema conquest_internal;
revoke all on function conquest_internal.conquest_submit_v1(text,text,jsonb) from public,anon,authenticated;
create function public.conquest_submit_v1(p_partition text,p_catalog text,p_events jsonb)
returns jsonb language plpgsql security definer set search_path='' as $$
declare paused boolean;
begin
  if auth.uid() is null then raise exception 'Authentication required';end if;
  perform 1 from conquest_internal.worlds where partition=p_partition for update;
  if not found then raise exception 'Unavailable conquest world';end if;
  select c.paused into paused from conquest_internal.intake_control c where c.partition=p_partition for update;
  if not found or paused then
    raise sqlstate 'PT503' using message='Conquest submissions temporarily unavailable';
  end if;
  return conquest_internal.conquest_submit_v1(p_partition,p_catalog,p_events);
end $$;
revoke all on function public.conquest_submit_v1(text,text,jsonb) from public,anon,authenticated;
grant execute on function public.conquest_submit_v1(text,text,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
