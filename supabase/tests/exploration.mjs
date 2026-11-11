import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile,readdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const db=new PGlite({extensions:{postgis}});
await db.waitReady;
await db.exec(`create role anon;create role authenticated;create schema auth;
create table auth.users(id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to authenticated,anon;
grant execute on function auth.uid() to authenticated,anon;`);
const root=new URL('../../',import.meta.url);
for(const file of (await readdir(new URL('supabase/sql-editor/',root))).filter(f=>f.endsWith('.sql')).sort().map(f=>'supabase/sql-editor/'+f)) {
 await db.exec(await readFile(new URL(file,root),'utf8')); console.log('Applied '+file);
}
assert.equal((await db.query('select count(*)::int n from public.exploration_targets')).rows[0].n,245);
const legacy='33333333-3333-4333-8333-333333333333';
await db.exec(`insert into auth.users values('${legacy}');
insert into public.exploration_targets(id,kind,region_id,dataset_version,area,dwell_ms,max_gap_ms,min_move,xp)
select 'osm-geofabrik-20261108-namyang-grid100-v1:'||coords,'cell','KR:SGIS:31241723','osm-geofabrik-20261108-namyang-grid100-v1',extensions.st_geomfromtext('POLYGON((0 0,1 0,1 1,0 1,0 0))'),3000,30000,8,10
from (values('35:-28'),('36:-29')) v(coords);
insert into public.exploration_grants values
('${legacy}','pleos-emulator','osm-geofabrik-20261108-namyang-grid100-v1:35:-28',1000,10),
('${legacy}','pleos-emulator','osm-geofabrik-20261108-namyang-grid100-v1:36:-29',2000,10);
select public.activate_grid500_v2();select public.activate_grid500_v2();
set request.jwt.claim.sub='${legacy}';set role authenticated;`);
const migrated=(await db.query("select public.sync_exploration_v1('pleos-emulator','[]'::jsonb) result")).rows[0].result;
assert.equal(migrated.xp,10);assert.equal(migrated.grants.length,1);
assert.equal(migrated.grants[0].target_id,'osm-geofabrik-20261108-namyang-grid500-v2:7:-6');
assert.equal(migrated.grants[0].earned_ms,1000);
await db.exec('reset role;');

const user='11111111-1111-4111-8111-111111111111';
await db.exec(`insert into auth.users values('${user}');set request.jwt.claim.sub='${user}';set role authenticated;`);
const now=Date.now()-15000;
const a={id:'a',time:now,lat:37.212744251,lon:126.823085900,accuracy:5};
const b={id:'b',time:now+5000,lat:37.212977903,lon:126.823360654,accuracy:5};
async function sync(samples,partition='pleos-emulator') {
 return (await db.query('select public.sync_exploration_v1($1,$2::jsonb) result',[partition,JSON.stringify(samples)])).rows[0].result;
}
const initial=await sync([a,b]);
assert.equal(initial.catalog_version,'osm-geofabrik-20261108-namyang-grid500-v2');
assert.equal(initial.xp,110);assert.equal(initial.grants.filter(x=>x.kind==='cell').length,1);
assert.deepEqual(await sync([a,b]),initial); // response-lost retry cannot pay twice
assert.equal((await sync([],'pleos')).xp,0); // emulator cannot increment real profile
assert.equal((await sync([{...a,id:'c',time:now+10000,accuracy:null},{...b,id:'d',time:now+14000}],'pleos')).xp,0);
let denied=false;try { await db.exec(`insert into public.exploration_grants values('${user}','pleos','KR:SGIS:31241723',1,99999)`); } catch { denied=true; }
assert.ok(denied,'direct XP write must be denied');
await db.exec(`reset role;insert into auth.users values('22222222-2222-4222-8222-222222222222');set request.jwt.claim.sub='22222222-2222-4222-8222-222222222222';set role authenticated;`);
assert.equal((await db.query('select count(*)::int n from public.exploration_grants')).rows[0].n,0);
await db.exec('reset role;set role anon;');
denied=false;try { await sync([]); } catch { denied=true; }
assert.ok(denied,'anonymous API key without user session must not execute RPC');
console.log('PASS: catalog, spatial unlock, XP, retry idempotency, test isolation, invalid accuracy, RLS, direct-write denial, unauthenticated denial');
await db.close();
