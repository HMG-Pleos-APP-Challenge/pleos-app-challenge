import {PGlite} from '@electric-sql/pglite';
import assert from 'node:assert/strict';
import {buildConcurrencySql} from '../conquest/hosted-concurrency.mjs';

const ref='hjqyiwqgukequtlrxgee',schema='conquest_race_abcdef123456';
const users=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222'];
await assert.rejects(()=>buildConcurrencySql('qcbesfuohbvprpkwmlqu',schema,users));
await assert.rejects(()=>buildConcurrencySql(ref,'conquest_internal',users));
await assert.rejects(()=>buildConcurrencySql(ref,schema,[users[0],users[0]]));
await assert.rejects(()=>buildConcurrencySql(ref,schema,['invalid',users[1]]));
const bundle=await buildConcurrencySql(ref,schema,users);
const db=new PGlite();
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);insert into auth.users values('${users[0]}'),('${users[1]}');
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    create schema conquest_test_control;create table conquest_test_control.environment(id integer,project_ref text);
    insert into conquest_test_control.environment values(1,'${ref}');
    create schema conquest_internal;create table conquest_internal.sentinel(value integer);insert into conquest_internal.sentinel values(13);
    create function public.conquest_snapshot_v1(text,text) returns integer language sql as $$ select 13 $$;`);
  await db.exec(bundle.sql);
  assert.equal((await db.query('select public.conquest_snapshot_v1(null,null) n')).rows[0].n,13);
  assert.equal((await db.query('select value from conquest_internal.sentinel')).rows[0].value,13);
  const permissions=(await db.query(`select has_schema_privilege('authenticated','${schema}','USAGE') allowed,
    (select bool_and(relrowsecurity) from pg_class c join pg_namespace n on n.oid=c.relnamespace
      where n.nspname='${schema}' and c.relkind='r') rls`)).rows[0];
  assert.equal(permissions.allowed,false);assert.equal(permissions.rls,true);
  const cutoff=Date.parse('2026-11-10T12:00:00+09:00');
  const accepted=(await db.query(`select ${schema}.accept_verified($1,'pleos','race-fixture','packet','ab',$2,$3,'fixture',$2) value`,
    [users[0],cutoff-1,'a'.repeat(64)])).rows[0].value;
  assert.equal(accepted.status,'ACCEPTED');
  const settled=(await db.query(`select ${schema}.settle_due('pleos',$1,7) value`,[cutoff])).rows[0].value;
  assert.equal(settled.processed,1);
  assert.equal((await db.query(`select owner from ${schema}.settlement_roads where partition='pleos' and road_id='ab'`)).rows[0].owner,'red');
  console.log('PASS concurrency bundle: target/schema/user guards, compiled clone, closed RLS, original schema/API preservation and settlement semantics. Single connection only; not a hosted race test.');
} finally {await db.close();}
