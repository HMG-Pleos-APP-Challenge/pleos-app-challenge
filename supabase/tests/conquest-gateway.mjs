import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const db=new PGlite({extensions:{postgis}});
const root=new URL('../../',import.meta.url);
const fixture=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8')).find(f=>f.name==='complete-forward');
const catalog='gateway-fixture';
const a='11111111-1111-4111-8111-111111111111';
const b='22222222-2222-4222-8222-222222222222';
const start=fixture.samples[0].time;
const finish=fixture.samples.at(-1).time;
let passed=0;
const hash=raw=>createHash('sha256').update(raw,'utf8').digest('hex');
function packet(suffix='') {
  const samples=fixture.samples.map(s=>({...s,id:s.id+suffix}));
  return JSON.stringify({eventId:samples.at(-1).id+':conquest:ab',roadId:'ab',occurredMs:samples.at(-1).time,samples});
}
async function rootRole() {await db.exec('reset role');}
async function login(user=a,role='authenticated') {
  await rootRole();await db.query("select set_config('request.jwt.claim.sub',$1,true)",[user??'']);
  await db.exec(`set local role ${role}`);
}
async function clock(ms) {await rootRole();await db.query('update conquest_internal.fixture_clock set ms=$1',[ms]);}
async function choose(user=a,team='red',partition='pleos') {
  await login(user);
  return (await db.query('select public.conquest_select_team_v1($1,$2,$3) result',[partition,catalog,team])).rows[0].result;
}
async function submit(events=[packet()],user=a,partition='pleos',version=catalog) {
  await login(user);
  return (await db.query('select public.conquest_submit_v1($1,$2,$3::jsonb) result',[partition,version,JSON.stringify(events)])).rows[0].result;
}
async function count() {
  await rootRole();return (await db.query('select count(*)::int n from conquest_internal.contributions')).rows[0].n;
}
async function reject(action) {
  await db.exec('savepoint rejection');let denied=false;
  try {await action();} catch {denied=true;}
  await db.exec('rollback to savepoint rejection;release savepoint rejection');
  assert.ok(denied,'Expected guarded rejection');
}
async function check(name,action) {
  await db.exec('begin');
  try {await action();passed++;console.log(`PASS ${passed}: ${name}`);}
  finally {await db.exec('rollback');}
}
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(f=>f.endsWith('.sql')).sort()) {
    await db.exec(await readFile(new URL(`supabase/sql-editor/${file}`,root),'utf8'));
  }
  for(const file of ['ledger','proof','gateway']) await db.exec(await readFile(new URL(`supabase/conquest/${file}-prototype.sql`,root),'utf8'));
  assert.ok((await db.query('select conquest_internal.admission_now_ms() ms')).rows[0].ms>0);
  // Isolated fixture clock only. The RPC signatures have no client clock override.
  await db.exec(`create table conquest_internal.fixture_clock(ms bigint);
    create or replace function conquest_internal.admission_now_ms() returns bigint language sql volatile
    set search_path='' as $$ select ms from conquest_internal.fixture_clock $$;`);
  await db.query('insert into conquest_internal.fixture_clock values($1)',[start-1000]);
  await db.query('insert into auth.users values($1),($2)',[a,b]);
  await db.exec("insert into conquest_internal.teams values('red'),('blue'),('green')");
  await db.query('insert into conquest_internal.catalogs values($1)',[catalog]);
  for(const [ordinal,road] of fixture.roads.entries()) {
    await db.query('insert into conquest_internal.roads values($1,$2)',[catalog,road.id]);
    await db.query(`insert into conquest_internal.road_geometry values($1,$2,$3,
      extensions.st_setsrid(extensions.st_geomfromgeojson($4),4326))`,[catalog,road.id,ordinal,
      JSON.stringify({type:'LineString',coordinates:road.points.map(([lat,lon])=>[lon,lat])})]);
  }
  await db.query("insert into conquest_internal.worlds values('pleos',$1),('pleos-emulator',$1)",[catalog]);
  await check('team endpoint derives caller and selection time from server',async()=>{
    const result=await choose();assert.equal(result.userId,a);assert.equal(result.selectedUtcMs,start-1000);
    assert.equal(result.status,'SELECTED');assert.equal(result.teamId,'red');
    await clock(finish);assert.equal((await choose()).selectedUtcMs,start-1000);
    assert.equal((await choose(a,'blue')).status,'TEAM_LOCKED');
    assert.equal((await choose(b,'green')).teamId,'green');
  });
  await check('authenticated proof submission returns exact immutable request hash',async()=>{
    await choose();await clock(finish+1);const result=await submit();
    assert.equal(result.userId,a);assert.equal(result.partition,'pleos');assert.equal(result.catalogVersion,catalog);
    assert.equal(result.protocol,1);assert.equal(result.receivedUtcMs,finish+1);
    assert.equal(result.results[0].requestHash,hash(packet()));
    assert.equal(result.results[0].status,'ACCEPTED');assert.equal(result.results[0].acknowledge,true);
    assert.equal(result.results[0].retryable,false);assert.equal(await count(),1);
  });
  await check('lost response replays acceptance after deadline but new late event is rejected',async()=>{
    await choose();await clock(finish+1);await submit();await clock(finish+86400000);
    const result=await submit([packet(),packet('-late')]);
    assert.deepEqual(result.results.map(r=>r.status),['ACCEPTED','LATE']);assert.equal(await count(),1);
  });
  await check('different IDs in the same half day produce a per-item duplicate result',async()=>{
    await choose();await clock(finish+1);
    assert.deepEqual((await submit([packet(),packet('-second')])).results.map(r=>r.status),['ACCEPTED','DUPLICATE_HALF_DAY']);
    assert.equal(await count(),1);
  });
  await check('wire changes under the same ID are blocked without acknowledging',async()=>{
    await choose();await clock(finish+1);await submit();
    const result=await submit([' '+packet()]);
    assert.equal(result.results[0].status,'CONFLICT');assert.equal(result.results[0].acknowledge,false);
    assert.equal(result.results[0].retryable,false);assert.equal(await count(),1);
  });
  await check('future result stays pending and becomes accepted under server time',async()=>{
    await choose();const first=await submit();
    assert.equal(first.results[0].status,'FUTURE');assert.equal(first.results[0].acknowledge,false);
    assert.equal(first.results[0].retryable,true);
    await clock(finish+1);assert.equal((await submit()).results[0].status,'ACCEPTED');
  });
  await check('two authenticated users have separate receipts and contributions',async()=>{
    await choose();await choose(b,'blue');await clock(finish+1);
    assert.equal((await submit()).userId,a);assert.equal((await submit([packet()],b)).userId,b);
    assert.equal(await count(),2);
  });
  await check('real and emulator submissions stay in separate membership and receipt scopes',async()=>{
    await choose();await choose(a,'blue','pleos-emulator');await clock(finish+1);
    assert.equal((await submit()).partition,'pleos');
    assert.equal((await submit([packet()],a,'pleos-emulator')).partition,'pleos-emulator');
    assert.equal(await count(),2);
  });
  await check('catalog change preserves old accepted retries but rejects new old-version events',async()=>{
    await choose();await clock(finish+1);await submit();await rootRole();
    await db.exec("insert into conquest_internal.catalogs values('next-catalog');update conquest_internal.worlds set catalog='next-catalog' where partition='pleos'");
    assert.deepEqual((await submit([packet(),packet('-new-old-version')])).results.map(r=>r.status),['ACCEPTED','WRONG_CATALOG']);
    assert.equal(await count(),1);
  });
  await check('no team and invalid proof are terminal item results without scores',async()=>{
    await clock(finish+1);assert.equal((await submit()).results[0].status,'NO_TEAM');
    await choose(b);const invalid=JSON.parse(packet('-invalid'));invalid.samples=invalid.samples.slice(2);
    const result=await submit([JSON.stringify(invalid)],b);
    assert.equal(result.results[0].status,'INVALID_PROOF');assert.equal(result.results[0].acknowledge,true);
    assert.equal(await count(),0);
  });
  await check('owner and clock spoofing fields, duplicates and malformed batch roll back',async()=>{
    await choose();await clock(finish+1);
    for(const events of [[packet(),packet()],[packet(),'{broken'],[JSON.stringify({...JSON.parse(packet()),userId:b})],
      [JSON.stringify({...JSON.parse(packet()),receivedUtcMs:finish+1})],[JSON.parse(packet())],[],Array(21).fill(packet())]) {
      await reject(()=>submit(events));assert.equal(await count(),0);
    }
    await reject(()=>submit([packet()],a,'mock'));await reject(()=>submit([packet()],a,'pleos','unknown'));
  });
  await check('unexpected failure in a later item rolls back the complete batch and hashes',async()=>{
    await choose();await clock(finish+1);await rootRole();
    await db.exec(`create function conquest_internal.fail_gateway_fixture() returns trigger language plpgsql as $$
      begin if new.event_id='e6-boom:conquest:ab' then raise exception 'Injected gateway failure';end if;return new;end $$;
      create trigger fail_later before insert on conquest_internal.receipts for each row
      execute function conquest_internal.fail_gateway_fixture();`);
    await reject(()=>submit([packet(),packet('-boom')]));
    assert.equal(await count(),0);
    assert.equal((await db.query('select count(*)::int n from conquest_internal.receipts')).rows[0].n,0);
    assert.equal((await db.query('select count(*)::int n from conquest_internal.gateway_receipts')).rows[0].n,0);
  });
  await check('no JWT subject and anon execution are rejected',async()=>{
    await reject(()=>submit([packet()],null));
    await login(a,'anon');await reject(()=>db.query('select public.conquest_submit_v1($1,$2,$3::jsonb)',
      ['pleos',catalog,JSON.stringify([packet()])]));assert.equal(await count(),0);
  });
  await check('client direct writes and internal function execution remain denied',async()=>{
    await login(a);await reject(()=>db.query("insert into conquest_internal.teams values('evil')"));
    await reject(()=>db.query('select conquest_internal.admission_now_ms()'));
    await rootRole();
    const functions=(await db.query(`select proname,prosecdef,proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where n.nspname='public' and proname in ('conquest_select_team_v1','conquest_submit_v1')`)).rows;
    assert.equal(functions.length,2);for(const f of functions) {assert.equal(f.prosecdef,true);assert.ok(f.proconfig.includes('search_path=""'));}
  });
  console.log(`PASS: ${passed} isolated authenticated gateway checks. JWT claims are fixtures, not a hosted login test.`);
} catch(error) {
  console.error({message:error.message,code:error.code,where:error.where});process.exitCode=1;
} finally {await db.close();}
