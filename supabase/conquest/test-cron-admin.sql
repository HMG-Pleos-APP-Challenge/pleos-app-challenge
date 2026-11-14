-- TEST PROJECT ONLY. Verify native Cron operator access; do not alter Supabase-owned policies.
do $$ begin
  if not exists(select 1 from conquest_test_control.environment where id=1 and project_ref='hjqyiwqgukequtlrxgee')
    then raise exception 'Wrong isolated Cron target';end if;
  if not exists(select 1 from pg_extension where extname='pg_cron') then raise exception 'Install test pg_cron first';end if;
  if has_schema_privilege('anon','cron','USAGE') or has_schema_privilege('authenticated','cron','USAGE')
    then raise exception 'App role Cron access requires operator review';end if;
  if exists(select 1 from cron.job where username<>'postgres') then raise exception 'Existing Cron users require separate review';end if;
  if (select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='cron' and c.relname in ('job','job_run_details') and c.relrowsecurity)<>2
    then raise exception 'Native Cron RLS needs review';end if;
end $$;
