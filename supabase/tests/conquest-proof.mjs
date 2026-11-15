import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';

const db=new PGlite({extensions:{postgis}});
const root=new URL('../../',import.meta.url);
const fixtures=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8'));
const user='11111111-1111-4111-8111-111111111111';
let passed=0;
async function check(name,action) {await action();passed++;console.log(`PASS ${passed}: ${name}`);}
async function replay(fixture) {
  return (await db.query('select conquest_internal.replay_proof($1,$2::jsonb) result',
    [fixture.name,JSON.stringify(fixture.samples)])).rows[0].result;
}
const valid=fixtures.find(f=>f.name==='complete-forward');
const end=valid.samples.at(-1);
async function submit(samples=valid.samples,{id=`${end.id}:conquest:ab`,road='ab',time=end.time,admitted=end.time+1}={}) {
  return (await db.query('select conquest_internal.submit_proof($1,$2,$3,$4,$5,$6,$7::jsonb,$8) result',
    [user,'pleos',valid.name,id,road,time,JSON.stringify(samples),admitted])).rows[0].result;
}
async function count(table) {
  assert.ok(['receipts','contributions'].includes(table));
  return (await db.query(`select count(*)::int n from conquest_internal.${table}`)).rows[0].n;
}
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(x=>x.endsWith('.sql')).sort()) {
    await db.exec(await readFile(new URL(`supabase/sql-editor/${file}`,root),'utf8'));
  }
  await db.exec(await readFile(new URL('supabase/conquest/ledger-prototype.sql',root),'utf8'));
  await db.exec(await readFile(new URL('supabase/conquest/proof-prototype.sql',root),'utf8'));
  for(const fixture of fixtures) {
    await db.query('insert into conquest_internal.catalogs values($1)',[fixture.name]);
    let roads=fixture.roads;
    if(fixture.runtimeCatalog) {
      const rows=(await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8')).trim().split(/\r?\n/).slice(2);
      roads=rows.map(line=>{const p=line.split('|');return {id:p[0],points:p[4].split(';').map(s=>s.split(',').map(Number))};});
    }
    const payload=JSON.stringify(roads.map((r,i)=>({id:r.id,ordinal:i,path:{type:'LineString',coordinates:r.points.map(([lat,lon])=>[lon,lat])}})));
    await db.query('insert into conquest_internal.roads select $1,id from jsonb_to_recordset($2::jsonb) as x(id text)',[fixture.name,payload]);
    await db.query(`insert into conquest_internal.road_geometry select $1,id,ordinal,
      extensions.st_setsrid(extensions.st_geomfromgeojson(path::text),4326)
      from jsonb_to_recordset($2::jsonb) as x(id text,ordinal integer,path jsonb)`,[fixture.name,payload]);
    await check(`shared replay ${fixture.name}`,async()=>{
      const result=await replay(fixture);
      assert.deepEqual(result.map(e=>e.roadId),fixture.expected);
      for(const event of result) {
        assert.equal(event.occurredMs,fixture.samples.at(-1).time);
        assert.equal(event.eventId,`${fixture.samples.at(-1).id}:conquest:${event.roadId}`);
      }
    });
  }
  await db.query('insert into auth.users values($1)',[user]);
  await db.exec("insert into conquest_internal.teams values('red')");
  await db.query("insert into conquest_internal.worlds values('pleos',$1)",[valid.name]);
  await db.query("select conquest_internal.select_team($1,'pleos','red',$2)",[user,valid.samples[0].time-1000]);
  await check('invalid proof and forged claims never score; final rejection is immutable',async()=>{
    await db.exec('begin');
    try {
      const missing=valid.samples.slice(2);
      assert.equal((await submit(missing)).status,'INVALID_PROOF');
      assert.equal((await submit(missing)).status,'INVALID_PROOF');
      assert.equal((await submit()).status,'CONFLICT');
      for(const claim of [{id:'bad-direction',road:'ba'},{id:'forged'},{id:'bad-time',time:end.time+1}]) {
        assert.equal((await submit(valid.samples,claim)).status,'INVALID_PROOF');
      }
      assert.equal(await count('receipts'),4);assert.equal(await count('contributions'),0);
    } finally {await db.exec('rollback');}
  });
  await check('verified proof creates one receipt and accepted contribution',async()=>{
    assert.equal((await submit()).status,'ACCEPTED');
    assert.equal(await count('receipts'),1);assert.equal(await count('contributions'),1);
  });
  await check('response-lost retry replays acceptance after deadline',async()=>{
    assert.equal((await submit(valid.samples,{admitted:end.time+86400000})).status,'ACCEPTED');
    assert.equal(await count('contributions'),1);
  });
  await check('modified proof for the same event conflicts and preserves acceptance',async()=>{
    const changed=valid.samples.map((s,i)=>i===2?{...s,lat:s.lat+.01}:s);
    assert.equal((await submit(changed)).status,'CONFLICT');
    assert.equal(await count('contributions'),1);
  });
  await check('raw coordinates and sample arrays are not retained in receipts',async()=>{
    const payload=(await db.query('select payload from conquest_internal.receipts')).rows[0].payload;
    assert.deepEqual(Object.keys(payload).sort(),['catalog','detector','occurred_ms','proof_hash','road']);
    assert.match(payload.proof_hash,/^[a-f0-9]{64}$/);
    assert.equal(payload.detector,'directed-road-v1');
  });
  await check('proof functions stay inaccessible to both client roles',async()=>{
    for(const role of ['anon','authenticated']) {
      const access=(await db.query(`select
        has_function_privilege($1,'conquest_internal.replay_proof(text,jsonb)','EXECUTE') replay,
        has_function_privilege($1,'conquest_internal.submit_proof(uuid,text,text,text,text,bigint,jsonb,bigint)','EXECUTE') submit,
        has_table_privilege($1,'conquest_internal.road_geometry','INSERT') insert_geometry`,[role])).rows[0];
      assert.deepEqual(access,{replay:false,submit:false,insert_geometry:false});
    }
  });
  console.log(`PASS: ${passed} isolated proof checks. No public API or deployed verifier.`);
} finally {await db.close();}
