import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import {readFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const db=new PGlite({extensions:{postgis}});
await db.waitReady;
await db.exec(`create role anon;create role authenticated;create schema auth;
create table auth.users(id uuid primary key);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to authenticated,anon;
grant execute on function auth.uid() to authenticated,anon;`);
const root=new URL('../../',import.meta.url); 
for(const file of ['supabase/migrations/202611100001_exploration.sql','supabase/migrations/202611100002_conquest_contract.sql','supabase/seed.sql']) {
 await db.exec(await readFile(new URL(file,root),'utf8')); console.log('Applied '+file);
}
assert.equal((await db.query('select count(*)::int n from public.exploration_targets')).rows[0].n,1581);
const user='11111111-1111-4111-8111-111111111111';
await db.exec(`insert into auth.users values('${user}');set request.jwt.claim.sub='${user}';set role authenticated;`);
const now=Date.now()-15000;
const a={id:'a',time:now,lat:37.212744251,lon:126.823085900,accuracy:5};
const b={id:'b',time:now+5000,lat:37.212977903,lon:126.823360654,accuracy:5};
async function sync(samples,partition='pleos-emulator') {
 return (await db.query('select public.sync_exploration_v1($1,$2::jsonb) result',[partition,JSON.stringify(samples)])).rows[0].result;
}
const initial=await sync([a,b]);
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
