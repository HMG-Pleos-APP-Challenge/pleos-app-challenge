// Consume actual immutable packets exported by the Kotlin capture test, not a JS reserialization.
import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const db=new PGlite({extensions:{postgis}});
const root=new URL('../../',import.meta.url);
const fixtures=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8'));
const packets=JSON.parse(await readFile(new URL('app/build/conquest-proof/captured.json',root),'utf8'));
assert.deepEqual(packets.map(p=>p.fixture).sort(),fixtures.filter(f=>f.expected.length===1).map(f=>f.name).sort());
const uid='11111111-1111-4111-8111-111111111111';
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;
    create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(f=>f.endsWith('.sql')).sort()) {
    await db.exec(await readFile(new URL(`supabase/sql-editor/${file}`,root),'utf8'));
  }
  for(const file of ['ledger','proof','gateway']) await db.exec(await readFile(new URL(`supabase/conquest/${file}-prototype.sql`,root),'utf8'));
  await db.query('insert into auth.users values($1)',[uid]);
  await db.exec("insert into conquest_internal.teams values('red');create table conquest_internal.fixture_clock(ms bigint);");
  await db.exec(`create or replace function conquest_internal.admission_now_ms() returns bigint language sql volatile
    set search_path='' as $$ select ms from conquest_internal.fixture_clock $$;`);
  for(const packet of packets) {
    await db.exec('begin');
    try {
      const fixture=fixtures.find(f=>f.name===packet.fixture);
      let roads=fixture.roads;
      if(fixture.runtimeCatalog) {
        roads=(await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8')).trim().split(/\r?\n/).slice(2)
          .map(line=>{const p=line.split('|');return {id:p[0],points:p[4].split(';').map(s=>s.split(',').map(Number))};});
      }
      await db.query('insert into conquest_internal.catalogs values($1)',[fixture.name]);
      const payload=JSON.stringify(roads.map((r,i)=>({id:r.id,ordinal:i,path:{type:'LineString',coordinates:r.points.map(([lat,lon])=>[lon,lat])}})));
      await db.query('insert into conquest_internal.roads select $1,id from jsonb_to_recordset($2::jsonb) as x(id text)',[fixture.name,payload]);
      await db.query(`insert into conquest_internal.road_geometry select $1,id,ordinal,extensions.st_setsrid(extensions.st_geomfromgeojson(path::text),4326)
        from jsonb_to_recordset($2::jsonb) as x(id text,ordinal integer,path jsonb)`,[fixture.name,payload]);
      await db.query("insert into conquest_internal.worlds values('pleos',$1)",[fixture.name]);
      const request=JSON.parse(packet.requestJson);
      await db.query('insert into conquest_internal.fixture_clock values($1)',[request.samples[0].time-1]);
      await db.query("select set_config('request.jwt.claim.sub',$1,true)",[uid]);
      await db.exec('set local role authenticated');
      await db.query("select public.conquest_select_team_v1('pleos',$1,'red')",[fixture.name]);
      await db.exec('reset role');
      await db.query('update conquest_internal.fixture_clock set ms=$1',[request.occurredMs+1]);
      await db.exec('set local role authenticated');
      const response=(await db.query("select public.conquest_submit_v1('pleos',$1,$2::jsonb) result",
        [fixture.name,JSON.stringify([packet.requestJson])])).rows[0].result;
      assert.equal(response.results[0].status,'ACCEPTED',packet.fixture);
      assert.equal(response.results[0].requestHash,packet.requestHash,packet.fixture);
      assert.equal(response.results[0].eventId,request.eventId);
      console.log(`PASS captured Kotlin packet: ${packet.fixture}`);
    } finally {await db.exec('rollback');}
  }
  console.log(`PASS: ${packets.length} real Kotlin-capture packets verified by isolated authenticated SQL gateway.`);
} catch(error) {console.error({message:error.message,code:error.code,where:error.where});process.exitCode=1;}
finally {await db.close();}
