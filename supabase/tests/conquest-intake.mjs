import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
import {buildTestStages} from '../conquest/prepare-test-env.mjs';

const db=new PGlite({extensions:{postgis}}),root=new URL('../../',import.meta.url);
const users=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222'];
const fixture=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8')).find(f=>f.runtimeCatalog);
const first=fixture.samples[0].time,last=fixture.samples.at(-1).time;
const bundle=await buildTestStages('abcdefghijklmnopqrst'),catalog=bundle.catalog;
let passed=0;
const admin=()=>db.exec('reset role');
async function login(user=users[0],role='authenticated') {
  await admin();await db.query("select set_config('request.jwt.claim.sub',$1,true)",[user??'']);
  assert.ok(['authenticated','anon'].includes(role));await db.exec(`set local role ${role}`);
}
async function clock(ms) {await admin();await db.query('update conquest_internal.fixture_clock set ms=$1',[ms]);}
async function state(partition='pleos') {await admin();return (await db.query('select conquest_internal.intake_status($1) value',[partition])).rows[0].value;}
async function change(paused,revision,reason='Fixture operation',partition='pleos') {
  await admin();return (await db.query('select conquest_internal.set_intake($1,$2,$3,$4) value',[partition,paused,revision,reason])).rows[0].value;
}
function packet(suffix='') {
  const samples=fixture.samples.map(s=>({...s,id:s.id+suffix}));
  return JSON.stringify({eventId:`${samples.at(-1).id}:conquest:${fixture.expected[0]}`,roadId:fixture.expected[0],occurredMs:last,samples});
}
async function submit(packets=[packet()],partition='pleos') {
  await login();return (await db.query('select public.conquest_submit_v1($1,$2,$3::jsonb) value',[partition,catalog,JSON.stringify(packets)])).rows[0].value;
}
async function count(table) {
  assert.ok(['receipts','gateway_receipts','contributions','intake_changes'].includes(table));
  await admin();return (await db.query(`select count(*)::int n from conquest_internal.${table}`)).rows[0].n;
}
async function reject(action,code='P0001') {
  await db.exec('savepoint expected_failure');let failure;
  try {await action();} catch(error) {failure=error;}
  await db.exec('rollback to savepoint expected_failure;release savepoint expected_failure');
  assert.equal(failure?.code,code);
}
async function check(name,action) {
  await admin();await db.exec('begin');
  try {await action();passed++;console.log(`PASS intake ${passed}: ${name}`);}
  finally {await admin();await db.exec('rollback');}
}
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  // Start on the fixture clock rather than changing the generated hosted start time.
  for(const stage of bundle.stages.filter(s=>!s.name.endsWith('conquest-start.sql'))) await db.exec(stage.sql);
  await db.exec(`create table conquest_internal.fixture_clock(ms bigint);
    create or replace function conquest_internal.admission_now_ms() returns bigint language sql volatile set search_path=''
      as $$ select ms from conquest_internal.fixture_clock $$;`);
  await db.query('insert into conquest_internal.fixture_clock values($1)',[first-1000]);
  await db.query('insert into auth.users values($1),($2)',users);
  await db.query("select conquest_internal.initialize_settlement('pleos',$1),conquest_internal.initialize_settlement('pleos-emulator',$1)",[first-1000]);
  await db.exec('begin');
  for(const partition of ['pleos','pleos-emulator']) {
    await login();await db.query('select public.conquest_select_team_v1($1,$2,$3)',[partition,catalog,'team-red']);
  }
  await admin();await db.exec('commit');
  await clock(last+1);
  await check('fresh installation is paused on both sources and initialized audit revision zero',async()=>{
    for(const p of ['pleos','pleos-emulator']) {const s=await state(p);assert.equal(s.paused,true);assert.equal(s.revision,0);assert.equal(s.worldConfigured,true);}
    assert.equal(await count('intake_changes'),2);
    await reject(()=>submit(),'PT503');assert.equal(await count('receipts'),0);
  });
  await check('only operator can inspect/change control or invoke the private implementation',async()=>{
    await login();
    await reject(()=>db.query("select conquest_internal.set_intake('pleos',false,0,'client')"),'42501');
    await reject(()=>db.query("select conquest_internal.intake_status('pleos')"),'42501');
    await reject(()=>db.query('select * from conquest_internal.intake_control'),'42501');
    await reject(()=>db.query('select conquest_internal.conquest_submit_v1($1,$2,$3::jsonb)',['pleos',catalog,JSON.stringify([packet()])]),'42501');
    await login(users[0],'anon');await reject(()=>db.query('select public.conquest_submit_v1($1,$2,$3::jsonb)',['pleos',catalog,'[]']),'42501');
  });
  await check('resume permits exact old gateway behavior; other source stays paused',async()=>{
    assert.equal((await change(false,0)).revision,1);
    const response=await submit();assert.equal(response.results[0].status,'ACCEPTED');assert.equal(await count('contributions'),1);
    await reject(()=>submit([packet('-other')],'pleos-emulator'),'PT503');
    assert.equal((await state('pleos-emulator')).revision,0);
  });
  await check('pause rejects entire mixed batch including replay without changing existing receipts',async()=>{
    await change(false,0);await submit();await change(true,1);
    await reject(()=>submit([packet(),packet('-new')]),'PT503');
    assert.equal(await count('receipts'),1);assert.equal(await count('gateway_receipts'),1);assert.equal(await count('contributions'),1);
    await change(false,2);await clock(last+86400000);
    const replay=await submit();assert.equal(replay.results[0].status,'ACCEPTED');assert.equal(await count('contributions'),1);
  });
  await check('pause never extends the existing first-submission deadline',async()=>{
    await reject(()=>submit(),'PT503');await clock(last+86400000);await change(false,0);
    assert.equal((await submit()).results[0].status,'LATE');assert.equal(await count('contributions'),0);
  });
  await check('stale operator CAS cannot resume a newer pause; no-op leaves audit unchanged',async()=>{
    await change(false,0);await change(true,1);
    await reject(()=>change(false,1));assert.equal((await state()).paused,true);assert.equal((await state()).revision,2);
    const total=await count('intake_changes');await change(true,2,'No-op reason');assert.equal(await count('intake_changes'),total);
    await reject(()=>change(false,2,''));await reject(()=>change(null,2));await reject(()=>state('mock'));
  });
  await check('missing control fails closed without recreating state',async()=>{
    await admin();await db.exec("delete from conquest_internal.intake_changes where partition='pleos';delete from conquest_internal.intake_control where partition='pleos'");
    await reject(()=>submit(),'PT503');await reject(()=>change(false,0));assert.equal(await count('receipts'),0);
  });
  await check('audit storage failure rolls back the state transition',async()=>{
    await admin();await db.exec(`create function pg_temp.fail_audit() returns trigger language plpgsql as $$ begin raise exception 'Audit failure';end $$;
      create trigger reject_audit before insert on conquest_internal.intake_changes for each row execute function pg_temp.fail_audit()`);
    await reject(()=>change(false,0));assert.equal((await state()).paused,true);assert.equal((await state()).revision,0);
  });
  await check('snapshot/membership and explicit team selection continue while paused',async()=>{
    await login(users[1]);
    assert.equal((await db.query('select public.conquest_membership_v1($1,$2) value',['pleos',catalog])).rows[0].value.status,'NO_TEAM');
    await db.query('select public.conquest_select_team_v1($1,$2,$3)',['pleos',catalog,'team-blue']);
    const snapshot=(await db.query('select public.conquest_snapshot_v1($1,$2) value',['pleos',catalog])).rows[0].value;
    assert.equal(snapshot.roads.length,1453);assert.equal(snapshot.summary.contributionCount,0);
    assert.equal(snapshot.summary.teamId,'team-blue');assert.equal((await state()).paused,true);
  });
  console.log(`PASS intake prototype: ${passed} checks; PGlite/controlled clock, not hosted concurrency or Android.`);
} catch(error) {console.error(`FAIL intake: ${error.code??error.name}: ${error.message}`);process.exitCode=1;}
finally {await db.close();}
