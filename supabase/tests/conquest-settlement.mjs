import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';

// Fresh in-memory Postgres only. No URL, key, hosted schema, real JWT or Cron job.
const db=new PGlite({extensions:{postgis}});
const root=new URL('../../',import.meta.url);
const start=Date.parse('2026-11-09T12:00:00+09:00');
const day=86400000, hour=3600000;
const catalog='settlement-fixture';
const a='11111111-1111-4111-8111-111111111111',b='22222222-2222-4222-8222-222222222222',c='33333333-3333-4333-8333-333333333333';
let passed=0;
const rootRole=()=>db.exec('reset role');
async function initialize(at=start,partition='pleos') {
  await rootRole();await db.query('select conquest_internal.initialize_settlement($1,$2)',[partition,at]);
}
async function settle(at=start+day,max=7,partition='pleos') {
  await rootRole();return (await db.query('select conquest_internal.settle_due($1,$2,$3) value',[partition,at,max])).rows[0].value;
}
async function choose(user=a,team='red',partition='pleos',at=start-2*day) {
  await rootRole();await db.query('insert into auth.users values($1) on conflict do nothing',[user]);
  await db.query('select conquest_internal.select_team($1,$2,$3,$4)',[user,partition,team,at]);
}
async function accept({user=a,id='event',road='ab',time=start+1,admitted=time,partition='pleos',version=catalog}={}) {
  await rootRole();return (await db.query('select conquest_internal.accept_verified($1,$2,$3,$4,$5,$6,$7,$8,$9) value',
    [user,partition,version,id,road,time,'a'.repeat(64),'settlement-fixture',admitted])).rows[0].value;
}
async function login(user=a,role='authenticated') {
  await rootRole();await db.query("select set_config('request.jwt.claim.sub',$1,true)",[user??'']);
  assert.ok(['anon','authenticated'].includes(role));await db.exec(`set local role ${role}`);
}
async function snapshot(user=a,partition='pleos',version=catalog) {
  await login(user);const result=(await db.query('select public.conquest_snapshot_v1($1,$2) value',[partition,version])).rows[0].value;
  await rootRole();return result;
}
async function count(table) {
  assert.ok(['settlement_scopes','settlement_runs','settlement_roads','contributions'].includes(table));
  await rootRole();return (await db.query(`select count(*)::int n from conquest_internal.${table}`)).rows[0].n;
}
async function reject(action) {
  await db.exec('savepoint expected_failure');let denied=false;
  try {await action();} catch {denied=true;}
  await db.exec('rollback to savepoint expected_failure;release savepoint expected_failure');
  assert.ok(denied,'Expected closed authorization/state boundary');
}
async function check(name,action) {
  await rootRole();await db.exec('begin');
  try {await action();passed++;console.log(`PASS ${passed}: ${name}`);} finally {await db.exec('rollback');}
}
const road=(state,id='ab')=>state.roads.find(r=>r.roadId===id);

try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$
      select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(f=>f.endsWith('.sql')).sort())
    await db.exec(await readFile(new URL(`supabase/sql-editor/${file}`,root),'utf8'));
  const exploration=(await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash;
  for(const file of ['ledger','proof','gateway','settlement'])
    await db.exec(await readFile(new URL(`supabase/conquest/${file}-prototype.sql`,root),'utf8'));
  await db.exec(`create table conquest_internal.fixture_clock(ms bigint);
    create or replace function conquest_internal.admission_now_ms() returns bigint language sql volatile set search_path=''
    as $$ select ms from conquest_internal.fixture_clock $$;`);
  await db.query('insert into conquest_internal.fixture_clock values($1)',[start+10*day]);
  await db.query('insert into auth.users values($1),($2),($3)',[a,b,c]);
  await db.exec("insert into conquest_internal.teams values('red'),('blue'),('green')");
  await db.query('insert into conquest_internal.catalogs values($1)',[catalog]);
  await db.query("insert into conquest_internal.roads values($1,'ab'),($1,'ba')",[catalog]);
  const proofFixture=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8')).find(f=>f.name==='complete-forward');
  for(const [ordinal,r] of proofFixture.roads.entries()) await db.query(`insert into conquest_internal.road_geometry values($1,$2,$3,
    extensions.st_setsrid(extensions.st_geomfromgeojson($4),4326))`,[catalog,r.id,ordinal,
    JSON.stringify({type:'LineString',coordinates:r.points.map(([lat,lon])=>[lon,lat])})]);
  await db.query("insert into conquest_internal.worlds values('pleos',$1),('pleos-emulator',$1)",[catalog]);

  await check('initialization is idempotent, neutral and starts strictly after a noon start',async()=>{
    await initialize();await initialize();const s=await snapshot();
    assert.equal(s.revision,0);assert.equal(s.lastSettlementUtcMs,null);assert.equal(s.nextSettlementUtcMs,start+day);
    assert.equal(s.roads.length,2);assert.ok(s.roads.every(r=>r.ownerTeamId===null&&r.reason==='INITIAL'));
    assert.equal((await settle(start+day-1)).processed,0);assert.equal(await count('settlement_runs'),0);
    await reject(()=>initialize(start+1));
  });
  await check('non-noon start uses the next KST noon and excludes pre-start history',async()=>{
    await choose();assert.equal((await accept({time:start+4*hour})).status,'ACCEPTED');
    await initialize(start+5*hour);await settle();const s=await snapshot();
    assert.equal(road(s).ownerTeamId,null);assert.equal(road(s).reason,'NO_CONTRIBUTIONS');
    assert.equal(s.summary.contributionCount,0);assert.equal(s.nextSettlementUtcMs,start+2*day);
  });
  await check('KST 36-hour lower bound is inclusive and one millisecond before it expires',async()=>{
    await initialize();await choose();await choose(b,'blue');
    const cutoff=start+2*day,lower=cutoff-36*hour;
    await accept({time:lower,id:'inside'});await accept({user:b,time:lower-1,id:'outside'});
    await settle(cutoff);const r=road(await snapshot());
    assert.deepEqual(r.scores,{red:1});assert.equal(r.ownerTeamId,'red');
  });
  await check('exact cutoff contributes immediately but ownership changes only in the next eligible round',async()=>{
    await initialize();await choose();await choose(b,'blue');await accept();
    await accept({user:b,time:start+day,id:'exact-noon'});await settle();
    assert.equal(road(await snapshot()).ownerTeamId,'red');assert.equal((await snapshot(b)).summary.contributionCount,1);
    await settle(start+2*day);assert.equal(road(await snapshot()).ownerTeamId,'blue');
  });
  await check('noon late submission is rejected while a previously accepted retry stays immutable',async()=>{
    await initialize();await choose();const original={time:start+day-1,id:'before-noon'};
    await accept(original);await settle();
    assert.equal((await accept({...original,admitted:start+day+1})).status,'ACCEPTED');
    assert.equal((await accept({time:start+day-1,admitted:start+day+1,id:'new-late'})).status,'LATE');
    assert.equal(await count('contributions'),1);assert.deepEqual(road(await snapshot()).scores,{red:1});
    assert.equal((await settle()).processed,0);
  });
  await check('settlement before a late first request has the same rejection boundary',async()=>{
    await initialize();await choose();await settle();
    assert.equal((await accept({time:start+day-1,admitted:start+day,id:'late'})).status,'LATE');
    assert.equal(road(await snapshot()).ownerTeamId,null);
    assert.equal((await accept({time:start+day,admitted:start+day,id:'on-time'})).status,'ACCEPTED');
    assert.equal((await snapshot()).summary.contributionCount,1);assert.equal(road(await snapshot()).ownerTeamId,null);
  });
  await check('bounded catch-up processes noons in order and duplicate execution creates no history',async()=>{
    await initialize();await choose();await accept();
    let s=await settle(start+3*day,1);assert.equal(s.processed,1);assert.equal(s.hasMore,true);
    s=await settle(start+3*day,1);assert.equal(s.revision,2);assert.equal(s.hasMore,true);
    s=await settle(start+3*day,1);assert.equal(s.revision,3);assert.equal(s.hasMore,false);
    assert.equal((await settle(start+3*day)).processed,0);assert.equal(await count('settlement_runs'),3);
    assert.equal(await count('settlement_roads'),6);assert.equal(road(await snapshot()).ownerTeamId,null);
  });
  await check('failed later noon rolls back earlier history, explanations and revision in the same call',async()=>{
    await initialize();await choose();await accept();
    await db.exec(`create function conquest_internal.fail_settlement_fixture() returns trigger language plpgsql as $$
      begin if new.cutoff_ms=${start+2*day} then raise exception 'Injected settlement failure';end if;return new;end $$;
      create trigger fail_later before insert on conquest_internal.settlement_roads for each row
      execute function conquest_internal.fail_settlement_fixture();`);
    await reject(()=>settle(start+2*day));
    assert.equal(await count('settlement_runs'),0);assert.equal(await count('settlement_roads'),0);
    assert.equal((await snapshot()).revision,0);
    await db.exec('drop trigger fail_later on conquest_internal.settlement_roads');
    assert.equal((await settle(start+2*day)).revision,2);
  });
  await check('snapshot is read-only and exposes overdue metadata without silently running settlement',async()=>{
    await initialize();const s=await snapshot();
    assert.ok(s.generatedUtcMs>s.nextSettlementUtcMs);assert.equal(s.revision,0);
    assert.equal(await count('settlement_runs'),0);assert.equal(s.summary.teamId,null);
  });
  await check('summary keeps lifetime recognized contributions while owned roads reflect the current team',async()=>{
    await initialize();await choose();await choose(b,'red');await choose(c,'blue');
    await accept();await accept({user:b,road:'ba',id:'teammate'});await settle();
    const s=await snapshot();assert.equal(s.summary.contributionCount,1);assert.equal(s.summary.ownedRoadCount,2);
    assert.equal((await snapshot(c)).summary.ownedRoadCount,0);
    await settle(start+4*day);const expired=await snapshot();
    assert.equal(expired.summary.ownedRoadCount,0);assert.equal(expired.summary.contributionCount,1);
    assert.equal(expired.summary.lastUpdatedUtcMs,start+4*day);
    assert.ok(!JSON.stringify(expired).includes(b));
  });
  await check('emulator and real worlds have independent revisions and owners',async()=>{
    await initialize();await initialize(start,'pleos-emulator');await choose();await accept();await settle();
    assert.equal((await snapshot()).revision,1);const emulator=await snapshot(a,'pleos-emulator');
    assert.equal(emulator.revision,0);assert.equal(emulator.summary.teamId,null);assert.equal(road(emulator).ownerTeamId,null);
  });
  await check('clock rewind, invalid catch-up bounds and uninitialized state fail closed',async()=>{
    await reject(()=>settle());await reject(()=>snapshot());await initialize();await settle();
    await reject(()=>settle(start));await reject(()=>settle(start+2*day,0));await reject(()=>settle(start+2*day,32));
    assert.equal((await snapshot()).revision,1);
  });
  await check('default operator clock uses the server clock provider without a client time parameter',async()=>{
    await initialize();await db.query('update conquest_internal.fixture_clock set ms=$1',[start+2*day]);
    const result=await settle(null);assert.equal(result.revision,2);assert.equal(result.hasMore,false);
  });
  await check('catalog rotation requires explicit migration and cannot reset completed ownership',async()=>{
    await initialize();await settle();await db.exec("insert into conquest_internal.catalogs values('new-version');update conquest_internal.worlds set catalog='new-version' where partition='pleos'");
    await reject(()=>settle(start+2*day));await reject(()=>snapshot());await reject(()=>initialize());
    assert.equal(await count('settlement_runs'),1);
  });
  await check('missing completed road rows or changed catalog size cannot yield a partial snapshot',async()=>{
    await initialize();await settle();await db.exec('savepoint damage');
    await db.exec("delete from conquest_internal.settlement_roads where road_id='ba'");
    await reject(()=>snapshot());await reject(()=>settle(start+2*day));await db.exec('rollback to savepoint damage');
    await db.query("insert into conquest_internal.roads values($1,'new-road')",[catalog]);
    await reject(()=>snapshot());await reject(()=>settle(start+2*day));
  });
  await check('database session timezone does not move KST noon boundaries',async()=>{
    await db.exec("set local timezone='America/Los_Angeles'");await initialize();
    assert.equal((await snapshot()).nextSettlementUtcMs,start+day);
    assert.equal((await settle(start+day-1)).processed,0);assert.equal((await settle()).processed,1);
  });
  await check('authenticated snapshot has no permission to run or rewrite settlement and anon is rejected',async()=>{
    await initialize();await login();
    await reject(()=>db.query('select conquest_internal.settle_due($1)',['pleos']));
    await reject(()=>db.query('select conquest_internal.initialize_settlement($1,$2)',['pleos',start]));
    await reject(()=>db.query('select * from conquest_internal.settlement_roads'));
    await reject(()=>db.query('update conquest_internal.settlement_scopes set revision=0'));
    await reject(()=>snapshot(null));await login(a,'anon');
    await reject(()=>db.query('select public.conquest_snapshot_v1($1,$2)',['pleos',catalog]));
    await rootRole();await reject(()=>snapshot(a,'mock'));await reject(()=>snapshot(a,'pleos','wrong'));
    const f=(await db.query("select prosecdef,proconfig from pg_proc where oid='public.conquest_snapshot_v1(text,text)'::regprocedure")).rows[0];
    assert.equal(f.prosecdef,true);assert.ok(f.proconfig.includes('search_path=""'));
  });
  await check('full 1453-direction catalog yields a complete bounded neutral and settled snapshot',async()=>{
    const lines=(await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8')).trim().split(/\r?\n/);
    const version=lines[1],ids=lines.slice(2).map(l=>l.split('|')[0]);assert.equal(ids.length,1453);
    await db.query('insert into conquest_internal.catalogs values($1)',[version]);
    await db.query('insert into conquest_internal.roads select $1,value from jsonb_array_elements_text($2::jsonb)',[version,JSON.stringify(ids)]);
    await db.query("update conquest_internal.worlds set catalog=$1 where partition='pleos'",[version]);
    await initialize();await choose();await accept({road:ids[0],version});await settle();
    const s=await snapshot(a,'pleos',version);
    assert.equal(s.roads.length,1453);assert.equal(new Set(s.roads.map(r=>r.roadId)).size,1453);
    assert.equal(s.summary.ownedRoadCount,1);assert.equal(await count('settlement_roads'),1453);
    const bytes=Buffer.byteLength(JSON.stringify(s));assert.ok(bytes<1048576);
    console.log(`INFO full snapshot UTF-8 bytes: ${bytes}`);
  });
  await check('existing exploration RPC stays byte-for-byte unchanged',async()=>{
    assert.equal((await db.query("select md5(prosrc) hash from pg_proc where oid='public.sync_exploration_v1(text,jsonb)'::regprocedure")).rows[0].hash,exploration);
  });
  await check('real Kotlin packet flows through authenticated proof gateway, settlement and snapshot without replay inflation',async()=>{
    const packets=JSON.parse(await readFile(new URL('app/build/conquest-proof/captured.json',root),'utf8'));
    const packet=packets.find(p=>p.fixture==='complete-forward');assert.ok(packet);
    const finish=JSON.parse(packet.requestJson).occurredMs,opened=proofFixture.samples[0].time-1000;
    await initialize(opened);await db.query('update conquest_internal.fixture_clock set ms=$1',[opened]);
    await login();await db.query('select public.conquest_select_team_v1($1,$2,$3)',['pleos',catalog,'red']);await rootRole();
    await db.query('update conquest_internal.fixture_clock set ms=$1',[finish+1]);
    async function submit(events) {
      await login();const result=(await db.query('select public.conquest_submit_v1($1,$2,$3::jsonb) value',
        ['pleos',catalog,JSON.stringify(events)])).rows[0].value;await rootRole();return result;
    }
    const accepted=await submit([packet.requestJson]);assert.equal(accepted.results[0].status,'ACCEPTED');
    assert.equal(accepted.results[0].requestHash,packet.requestHash);
    const cutoff=Number((await db.query('select conquest_internal.latest_noon_ms($1)+86400000 n',[finish])).rows[0].n);
    await db.query('update conquest_internal.fixture_clock set ms=$1',[cutoff]);await settle(null);
    const before=await snapshot();assert.equal(road(before).ownerTeamId,'red');assert.equal(before.summary.contributionCount,1);
    await db.query('update conquest_internal.fixture_clock set ms=$1',[cutoff+1]);
    const late=JSON.parse(packet.requestJson);late.samples.forEach(s=>s.id+='-late');
    late.eventId=late.samples.at(-1).id+':conquest:'+late.roadId;
    const retry=await submit([packet.requestJson,JSON.stringify(late)]);
    assert.deepEqual(retry.results.map(r=>r.status),['ACCEPTED','LATE']);
    const after=await snapshot();assert.deepEqual(after.roads,before.roads);assert.equal(after.revision,before.revision);
    assert.equal(after.summary.contributionCount,1);assert.equal(after.summary.ownedRoadCount,1);
    assert.equal((await snapshot(b)).summary.contributionCount,0);
  });
  const parity=JSON.parse(await readFile(new URL('app/build/conquest-proof/settlements.json',root),'utf8'));
  assert.equal(parity.length,7,'Run tools/build-local.ps1 to generate Kotlin parity results first');
  for(const fixture of parity) await check(`actual Kotlin settlement parity: ${fixture.name}`,async()=>{
    await initialize(fixture.startedMs);
    for(const member of fixture.members) await choose(member.userId,member.teamId,'pleos',fixture.startedMs);
    for(const event of fixture.events)
      assert.equal((await accept({user:event.userId,id:event.eventId,road:event.roadId,time:event.occurredMs})).status,'ACCEPTED');
    for(const expected of fixture.settlements) {
      await settle(expected.cutoffMs);
      const s=await snapshot();assert.equal(s.lastSettlementUtcMs,expected.cutoffMs);
      assert.deepEqual(s.roads,expected.roads);
    }
  });
  console.log(`PASS: ${passed} isolated settlement/snapshot checks. No hosted Auth, Cron or multi-connection concurrency test.`);
} catch(error) {
  console.error({message:error.message,code:error.code,where:error.where});process.exitCode=1;
} finally {await db.close();}
