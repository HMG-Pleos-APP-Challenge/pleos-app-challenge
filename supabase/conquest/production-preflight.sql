-- Metadata only. Run with CLI db query --remote=<explicit-ref> --read-only --file <this-file>.
-- No application rows, DDL, migrations, scheduler changes or settlement execution.
select jsonb_build_object(
  'serverVersion',current_setting('server_version'),
  'databaseTimezone',current_setting('TimeZone'),
  'conquestInternalExists',to_regnamespace('conquest_internal') is not null,
  'cronInstalled',exists(select 1 from pg_extension where extname='pg_cron'),
  'cronTimezone',current_setting('cron.timezone',true),
  'tables',coalesce((select jsonb_agg(jsonb_build_object(
      'schema',n.nspname,'name',c.relname,'rls',c.relrowsecurity,
      'anonWrite',has_table_privilege('anon',c.oid,'INSERT,UPDATE,DELETE'),
      'authenticatedWrite',has_table_privilege('authenticated',c.oid,'INSERT,UPDATE,DELETE'))
      order by n.nspname,c.relname)
    from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname in ('public','conquest_internal') and c.relkind='r'),'[]'::jsonb),
  'functions',coalesce((select jsonb_agg(jsonb_build_object(
      'name',p.proname,'arguments',pg_get_function_identity_arguments(p.oid),
      'bodyHash',md5(p.prosrc),'securityDefiner',p.prosecdef,'settings',p.proconfig,
      'anonExecute',has_function_privilege('anon',p.oid,'EXECUTE'),
      'authenticatedExecute',has_function_privilege('authenticated',p.oid,'EXECUTE')) order by p.proname)
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='public' and (p.proname like 'conquest_%' or p.proname='sync_exploration_v1')),'[]'::jsonb),
  'reservedColumns',coalesce((select jsonb_agg(jsonb_build_object(
      'table',c.table_name,'column',c.column_name,'type',c.udt_name,'nullable',c.is_nullable)
      order by c.table_name,c.ordinal_position)
    from information_schema.columns c where c.table_schema='public'
      and c.table_name in ('conquest_road_catalog','conquest_ownership')),'[]'::jsonb)
) value;
