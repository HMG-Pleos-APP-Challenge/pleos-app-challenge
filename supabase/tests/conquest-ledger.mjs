import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';

// A fresh in-memory database only. No Supabase URL, credentials or network requests.
const db=new PGlite({extensions:{postgis}});
const root=new URL('../../',import.meta.url);
const catalogLines=(await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8')).trim().split(/\r?\n/);
const catalog=catalogLines[1];
const roads=catalogLines.slice(2).map(line=>line.split('|')[0]);
const a='11111111-1111-4111-8111-111111111111';
const b='22222222-2222-4222-8222-222222222222';
const c='33333333-3333-4333-8333-333333333333';
const noon=Date.parse('2026-11-13T12:00:00+09:00');
const minute=60_000;
const hour=60*minute;
const proof='a'.repeat(64);
let passed=0;

async function choose(user=a,team='red',partition='pleos',time=noon-24*hour) {
  return (await db.query('select conquest_internal.select_team($1,$2,$3,$4) result',[user,partition,team,time])).rows[0].result;
}
async function accept({user=a,partition='pleos',version=catalog,id='event',road=roads[0],time=noon+minute,
  admitted=time+1,hash=proof,detector='fixture-v1'}={}) {
  return (await db.query('select conquest_internal.accept_verified($1,$2,$3,$4,$5,$6,$7,$8,$9) result',
    [user,partition,version,id,road,time,hash,detector,admitted])).rows[0].result;
}
async function count(table) {
  assert.ok(['contributions','receipts','memberships'].includes(table));
  return (await db.query(`select count(*)::int n from conquest_internal.${table}`)).rows[0].n;
}
async function rejected(query,params=[]) {
  await db.exec('savepoint expected_failure');
  let failed=false;
  try { await db.query(query,params); } catch { failed=true; }
  await db.exec('rollback to savepoint expected_failure;release savepoint expected_failure');
  assert.ok(failed,'Expected SQL rejection');
}
async function test(name,action) {
  await db.exec('begin');
  try { await action();passed++;console.log(`PASS ${passed}: ${name}`); }
  finally { await db.exec('rollback'); }
}

try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;`);
  for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(x=>x.endsWith('.sql')).sort()) {
    await db.exec(await readFile(new URL(`supabase/sql-editor/${file}`,root),'utf8'));
  }
  const originalFunction=(await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash;
  await db.exec(await readFile(new URL('supabase/conquest/ledger-prototype.sql',root),'utf8'));
  await db.query('insert into auth.users select value::uuid from jsonb_array_elements_text($1::jsonb)',[JSON.stringify([a,b,c])]);
  await db.exec("insert into conquest_internal.teams values('red'),('blue'),('green')");
  await db.query('insert into conquest_internal.catalogs values($1)',[catalog]);
  await db.query('insert into conquest_internal.roads select $1,value from jsonb_array_elements_text($2::jsonb)',[catalog,JSON.stringify(roads)]);
  await db.query("insert into conquest_internal.worlds values('pleos',$1),('pleos-emulator',$1)",[catalog]);

  await test('runtime catalog has 1453 unique directed IDs and preserves legacy IDs',async()=>{
    assert.equal(roads.length,1453);assert.equal(new Set(roads).size,1453);
    const legacy=(await readFile(new URL('app/src/main/assets/conquest/namyang-legacy.segments',root),'utf8')).trim().split(/\r?\n/).slice(2);
    assert.equal(legacy.length,6);
    for(const line of legacy) assert.ok(roads.includes(line.split('|')[0]));
  });
  await test('first team wins and retry preserves its selection time',async()=>{
    assert.equal(await choose(),'SELECTED');
    assert.equal(await choose(a,'red','pleos',noon),'UNCHANGED');
    assert.equal(await choose(a,'blue'),'TEAM_LOCKED');
    assert.equal((await db.query('select selected_ms::text ms from conquest_internal.memberships')).rows[0].ms,String(noon-24*hour));
    assert.equal(await count('memberships'),1);
  });
  await test('three teams remain data driven',async()=>{
    for(const [user,team] of [[a,'red'],[b,'blue'],[c,'green']]) {
      assert.equal(await choose(user,team),'SELECTED');
      assert.equal((await accept({user,id:team})).status,'ACCEPTED');
    }
    assert.equal(await count('contributions'),3);
  });
  await test('same event replays original result after the noon deadline',async()=>{
    await choose();
    const event={time:noon-minute,admitted:noon-1};
    const first=await accept(event);
    assert.equal(first.status,'ACCEPTED');
    assert.deepEqual(await accept({...event,admitted:noon+minute}),first);
    assert.equal(await count('contributions'),1);assert.equal(await count('receipts'),1);
  });
  await test('same event with changed proof, road, time, detector or catalog conflicts',async()=>{
    await choose();await accept();
    for(const changed of [{hash:'b'.repeat(64)},{road:roads[1]},{time:noon+2*minute},{detector:'other'},{version:'other'}]) {
      assert.equal((await accept(changed)).status,'CONFLICT');
    }
    assert.equal(await count('contributions'),1);assert.equal(await count('receipts'),1);
    assert.equal((await accept()).status,'ACCEPTED');
  });
  await test('different IDs for the same account and half day count once',async()=>{
    await choose();await accept();
    assert.equal((await accept({id:'second',time:noon+2*minute})).status,'DUPLICATE_HALF_DAY');
    assert.equal(await count('contributions'),1);assert.equal(await count('receipts'),2);
  });
  await test('opposite directions and different accounts are independent',async()=>{
    await choose();await choose(b,'blue');
    assert.equal((await accept()).status,'ACCEPTED');
    assert.equal((await accept({id:'reverse',road:roads[1]})).status,'ACCEPTED');
    assert.equal((await accept({user:b})).status,'ACCEPTED');
    assert.equal(await count('contributions'),3);
  });
  await test('noon switches half day while a newly late event is rejected',async()=>{
    await choose();
    assert.equal((await accept({id:'morning',time:noon-1,admitted:noon-1})).status,'ACCEPTED');
    assert.equal((await accept({id:'noon',time:noon,admitted:noon})).status,'ACCEPTED');
    assert.equal((await accept({id:'late',time:noon-1,admitted:noon+minute})).status,'LATE');
    assert.equal(await count('contributions'),2);
  });
  await test('KST midnight switches date independently of database timezone',async()=>{
    await db.exec("set local time zone 'America/Los_Angeles'");await choose();
    const midnight=noon+12*hour;
    assert.equal((await accept({id:'before',time:midnight-1})).status,'ACCEPTED');
    assert.equal((await accept({id:'after',time:midnight})).status,'ACCEPTED');
    const dates=(await db.query("select game_date::text d,afternoon from conquest_internal.contributions order by occurred_ms")).rows;
    assert.deepEqual(dates,[{d:'2026-11-13',afternoon:true},{d:'2026-11-14',afternoon:false}]);
  });
  await test('future timestamp stays retryable without a terminal receipt',async()=>{
    await choose();
    assert.equal((await accept({admitted:noon})).status,'FUTURE');
    assert.equal(await count('receipts'),0);
    assert.equal((await accept()).status,'ACCEPTED');
  });
  await test('default admission uses database wall clock without a client timestamp',async()=>{
    await choose(a,'red','pleos',0);
    const result=(await db.query(`select conquest_internal.accept_verified($1,$2,$3,$4,$5,
      floor(extract(epoch from clock_timestamp())*1000)::bigint+86400000,$6,$7) result`,
      [a,'pleos',catalog,'future-db-clock',roads[0],proof,'fixture-v1'])).rows[0].result;
    assert.equal(result.status,'FUTURE');assert.equal(await count('receipts'),0);
  });
  await test('no team, before team selection, wrong catalog and unknown road never score',async()=>{
    assert.equal((await accept({id:'no-team'})).status,'NO_TEAM');
    await choose(a,'red','pleos',noon+2*minute);
    assert.equal((await accept({id:'early'})).status,'BEFORE_TEAM_SELECTION');
    assert.equal((await accept({id:'catalog',version:'unknown'})).status,'WRONG_CATALOG');
    assert.equal((await accept({id:'road',road:'unknown'})).status,'UNKNOWN_ROAD');
    assert.equal(await count('contributions'),0);
  });
  await test('real and emulator namespaces do not share membership or receipts',async()=>{
    await choose();await choose(a,'blue','pleos-emulator');
    assert.equal((await accept()).status,'ACCEPTED');
    assert.equal((await accept({partition:'pleos-emulator'})).status,'ACCEPTED');
    assert.equal(await count('contributions'),2);
    const teams=(await db.query('select distinct team_id from conquest_internal.contributions order by team_id')).rows;
    assert.deepEqual(teams,[{team_id:'blue'},{team_id:'red'}]);
  });
  await test('invalid input and MOCK partition cannot create receipt or membership',async()=>{
    await rejected('select conquest_internal.select_team($1,$2,$3,$4)',[a,'mock','red',noon]);
    await rejected('select conquest_internal.select_team($1,$2,$3,$4)',[a,'pleos','unknown',noon]);
    await rejected('select conquest_internal.accept_verified($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [a,'pleos',catalog,'invalid',roads[0],noon,'bad-proof','fixture',noon]);
    assert.equal(await count('memberships'),0);assert.equal(await count('receipts'),0);
  });
  await test('receipt and accepted contribution roll back together on storage failure',async()=>{
    await choose();
    await db.exec(`create function conquest_internal.fail_fixture() returns trigger language plpgsql as $$
      begin raise exception 'Injected storage failure';end $$;
      create trigger fail_insert before insert on conquest_internal.contributions
      for each row execute function conquest_internal.fail_fixture();`);
    await rejected('select conquest_internal.accept_verified($1,$2,$3,$4,$5,$6,$7,$8,$9)',
      [a,'pleos',catalog,'atomic',roads[0],noon,proof,'fixture',noon]);
    assert.equal(await count('receipts'),0);assert.equal(await count('contributions'),0);
  });
  await test('client roles have neither private function execution nor table access',async()=>{
    for(const role of ['anon','authenticated']) {
      const grants=(await db.query(`select
        has_schema_privilege($1,'conquest_internal','USAGE') schema_access,
        has_table_privilege($1,'conquest_internal.contributions','INSERT') can_insert,
        has_function_privilege($1,'conquest_internal.select_team(uuid,text,text,bigint)','EXECUTE') can_choose,
        has_function_privilege($1,'conquest_internal.accept_verified(uuid,text,text,text,text,bigint,text,text,bigint)','EXECUTE') can_accept`,[role])).rows[0];
      assert.deepEqual(grants,{schema_access:false,can_insert:false,can_choose:false,can_accept:false});
      await db.exec(`set local role ${role}`);
      await rejected('select conquest_internal.select_team($1,$2,$3,$4)',[a,'pleos','red',noon]);
      await db.exec('reset role');
    }
  });
  await test('exploration function and all 245 catalog targets remain unchanged',async()=>{
    assert.equal((await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash,originalFunction);
    assert.equal((await db.query('select count(*)::int n from public.exploration_targets')).rows[0].n,245);
    assert.equal((await db.query('select count(*)::int n from public.exploration_grants')).rows[0].n,0);
  });
  console.log(`PASS: ${passed} isolated conquest ledger checks. No deployed RPC or driving proof verifier.`);
} finally { await db.close(); }
