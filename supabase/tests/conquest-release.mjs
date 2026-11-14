import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {buildReleaseSql,isolateRelease} from '../conquest/build-release.mjs';
import {buildTestStages} from '../conquest/prepare-test-env.mjs';
import {snapshotSql,restoreSql,stateDigest} from '../conquest/release-state.mjs';

const db=new PGlite({extensions:{postgis}}),root=new URL('../../',import.meta.url);
const users=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222'];
const names=(await readdir(new URL('supabase/migrations/',root))).filter(n=>/^\d{14}_conquest_server_v1\.sql$/.test(n));
assert.equal(names.length,1);
const actual=(await readFile(new URL(`supabase/migrations/${names[0]}`,root),'utf8')).replaceAll('\r\n','\n');
const generated=await buildReleaseSql();assert.equal(actual,generated.sql);
assert.throws(()=>isolateRelease(actual,'conquest_internal'));
let checks=0;
const ok=name=>{checks++;console.log(`PASS release ${checks}: ${name}`);};
async function denied(sql,params,code) {
  await db.exec('savepoint denial');let failure;
  try {await db.query(sql,params);} catch(error) {failure=error;}
  await db.exec('rollback to savepoint denial;release savepoint denial');assert.equal(failure?.code,code);
}
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const n of (await readdir(new URL('supabase/sql-editor/',root))).filter(n=>n.endsWith('.sql')).sort())
    await db.exec(await readFile(new URL(`supabase/sql-editor/${n}`,root),'utf8'));
  const original=(await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash;
  await db.exec(actual);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.worlds')).rows[0].n,0);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.intake_control where paused and revision=0')).rows[0].n,2);
  assert.equal((await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash,original);
  ok('atomic migration installs closed, without world/catalog/start seeds or exploration changes');
  const clocks=(await db.query(`select count(*)::int n from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where n.nspname='conquest_internal' and (array_to_string(proargnames,',') like '%p_test_%' or proname='fixture_clock')`)).rows[0].n;
  assert.equal(clocks,0);assert.ok(!actual.includes('p_test_'));
  ok('release artifact and installed functions have no test clock argument');
  const ref='abcdefghijklmnopqrst',bundle=await buildTestStages(ref);
  await db.exec(`create schema conquest_test_control;create table conquest_test_control.environment(id integer,project_ref text);
    insert into conquest_test_control.environment values(1,'${ref}');`);
  await db.exec(bundle.stages.find(s=>s.name.endsWith('conquest-catalog.sql')).sql);
  await db.query('insert into auth.users values($1),($2)',users);
  const now=(await db.query('select conquest_internal.admission_now_ms() n')).rows[0].n;
  await db.query("select conquest_internal.initialize_settlement('pleos',$1),conquest_internal.initialize_settlement('pleos-emulator',$2)",[now-60000,now-4*86400000]);
  await db.query("select conquest_internal.select_team($1,'pleos','team-red',$2)",[users[0],now-60000]);
  const fixture=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8')).find(f=>f.runtimeCatalog);
  const samples=fixture.samples.map((s,i)=>({...s,id:`release-${i}`,time:now-1000+s.time-fixture.samples.at(-1).time}));
  const packet=JSON.stringify({eventId:`${samples.at(-1).id}:conquest:${fixture.expected[0]}`,roadId:fixture.expected[0],occurredMs:samples.at(-1).time,samples});
  await db.exec('begin');await db.query("select set_config('request.jwt.claim.sub',$1,true)",[users[0]]);await db.exec('set local role authenticated');
  await denied('select public.conquest_submit_v1($1,$2,$3::jsonb)',['pleos',bundle.catalog,JSON.stringify([packet])],'PT503');
  await denied("select conquest_internal.settle_due('pleos')",[],'42501');
  await db.exec('reset role');await db.query("select conquest_internal.set_intake('pleos',false,0,'Release fixture')");
  await db.exec('set local role authenticated');
  const first=(await db.query('select public.conquest_submit_v1($1,$2,$3::jsonb) value',['pleos',bundle.catalog,JSON.stringify([packet])])).rows[0].value;
  assert.equal(first.results[0].status,'ACCEPTED');
  const retry=(await db.query('select public.conquest_submit_v1($1,$2,$3::jsonb) value',['pleos',bundle.catalog,JSON.stringify([packet])])).rows[0].value;
  assert.deepEqual(retry.results,first.results);
  const snapshot=(await db.query('select public.conquest_snapshot_v1($1,$2) value',['pleos',bundle.catalog])).rows[0].value;
  assert.equal(snapshot.roads.length,1453);assert.equal(snapshot.summary.contributionCount,1);
  await db.exec('reset role;commit');
  ok('public proof submission, pause, permission fencing, exact retry and full snapshot work on real clock');
  const one=(await db.query("select conquest_internal.settle_due('pleos-emulator',1) value")).rows[0].value;
  assert.equal(one.processed,1);assert.equal(one.hasMore,true);
  const rest=(await db.query("select conquest_internal.settle_due('pleos-emulator',31) value")).rows[0].value;
  assert.ok(rest.processed>=2);assert.equal(rest.hasMore,false);
  assert.equal((await db.query("select conquest_internal.settle_due('pleos-emulator') value")).rows[0].value.processed,0);
  ok('bounded missed-noon catch-up and idempotency use server clock without overrides');
  await db.exec('begin');await denied("select conquest_internal.settle_due('pleos',0::bigint,7)",[],'42883');await db.exec('commit');
  await assert.rejects(()=>db.exec(actual));await db.exec('rollback');
  assert.equal((await db.query('select count(*)::int n from conquest_internal.contributions')).rows[0].n,1);
  ok('clock overload and automatic reinstall/reset are refused; committed contribution preserved');
  const source='conquest_release_111111111111',target='conquest_release_222222222222';
  await db.exec(isolateRelease(actual,source));await db.exec(isolateRelease(actual,target));
  await db.exec(`insert into ${source}.teams values('red');insert into ${source}.catalogs values('restore-fixture');
    insert into ${source}.roads values('restore-fixture','ab');insert into ${source}.worlds values('pleos','restore-fixture');
    insert into ${source}.road_geometry values('restore-fixture','ab',0,extensions.st_geomfromtext('LINESTRING(126.8 37.2,126.802 37.2)',4326));`);
  await db.query(`select ${source}.initialize_settlement('pleos',$1),${source}.select_team($2,'pleos','red',$1)`,[now-3*86400000,users[0]]);
  await db.query(`select ${source}.accept_verified($1,'pleos','restore-fixture','restore-event','ab',$2,$3,'restore-fixture',$2)`,[users[0],now-86400000,'a'.repeat(64)]);
  await db.query(`select ${source}.settle_due('pleos')`);
  const saved=(await db.query(snapshotSql(source))).rows[0].value;
  await db.exec(restoreSql(target,saved));
  const restored=(await db.query(snapshotSql(target))).rows[0].value;
  assert.deepEqual(restored,saved);assert.equal(stateDigest(restored),stateDigest(saved));
  assert.ok(saved.settlement_runs.length>0&&saved.contributions.length>0&&saved.road_geometry[0].path_ewkb);
  ok('nonempty logical restore preserves geometry, receipts, contributions, cyclic settlement pointers and audit');
  await assert.rejects(()=>db.exec(restoreSql(target,saved)));await db.exec('rollback');
  assert.throws(()=>restoreSql('conquest_internal',saved));
  assert.deepEqual((await db.query(snapshotSql(target))).rows[0].value,saved);
  ok('restore refuses existing/non-rehearsal targets and preserves original restored rows');
  console.log(`PASS migration: ${names[0]}, ${checks} checks; PGlite, not an operating deployment or hosted Cron.`);
} catch(error) {console.error(`FAIL release: ${error.code??error.name}: ${error.message}`);process.exitCode=1;}
finally {await db.close();}
