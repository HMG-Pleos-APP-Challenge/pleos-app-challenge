-- Metadata only. Does not create/repair history or read account/location rows.
select jsonb_build_object(
  'columns',(select jsonb_agg(jsonb_build_object('table',c.relname,'name',a.attname,'ordinal',a.attnum,
      'type',t.typname,'typeSchema',tn.nspname,'typmod',a.atttypmod,'notNull',a.attnotnull,
      'default',pg_get_expr(d.adbin,d.adrelid)) order by c.relname,a.attnum)
    from pg_class c join pg_namespace n on n.oid=c.relnamespace join pg_attribute a on a.attrelid=c.oid
    join pg_type t on t.oid=a.atttypid join pg_namespace tn on tn.oid=t.typnamespace
    left join pg_attrdef d on d.adrelid=c.oid and d.adnum=a.attnum
    where n.nspname='public' and c.relkind='r' and a.attnum>0 and not a.attisdropped),
  'constraints',(select jsonb_agg(jsonb_build_object('table',c.relname,'name',k.conname,'kind',k.contype,
      'definition',pg_get_constraintdef(k.oid,true)) order by c.relname,k.conname)
    from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace
    -- PG18 records NOT NULL in pg_constraint; PG17 records it only in attnotnull.
    -- The columns comparison above checks attnotnull on both versions.
    where n.nspname='public' and k.contype<>'n'),
  'indexes',(select jsonb_agg(jsonb_build_object('table',tablename,'name',indexname,'definition',indexdef)
    order by tablename,indexname) from pg_indexes where schemaname='public'),
  'tables',(select jsonb_agg(jsonb_build_object('name',c.relname,'rls',c.relrowsecurity,
    'anonWrite',has_table_privilege('anon',c.oid,'INSERT,UPDATE,DELETE'),
    'authWrite',has_table_privilege('authenticated',c.oid,'INSERT,UPDATE,DELETE')) order by c.relname)
    from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind='r'),
  'policies',(select jsonb_agg(jsonb_build_object('table',tablename,'name',policyname,'roles',roles,
    'command',cmd,'using',qual,'check',with_check) order by tablename,policyname) from pg_policies where schemaname='public'),
  'functions',(select jsonb_agg(jsonb_build_object('name',p.proname,'args',pg_get_function_identity_arguments(p.oid),
    'bodyHashLF',md5(replace(p.prosrc,chr(13)||chr(10),chr(10))),'definer',p.prosecdef,'settings',p.proconfig,
    'anonExecute',has_function_privilege('anon',p.oid,'EXECUTE'),'authExecute',has_function_privilege('authenticated',p.oid,'EXECUTE')) order by p.proname)
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'
      and p.proname in ('sync_exploration_v1','activate_grid500_v2'))
) value;
