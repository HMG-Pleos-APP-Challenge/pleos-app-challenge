import {readFile,writeFile} from 'node:fs/promises';
import {parseArgs} from 'node:util';
import {createHash,randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {productionRef} from './prepare-test-env.mjs';

// Explicit isolated target only. Uses public key + two real anonymous user sessions, never service_role.
const {values}=parseArgs({options:{config:{type:'string'},'project-ref':{type:'string'},report:{type:'string'}}});
const ref=values['project-ref'];
if(!/^[a-z]{20}$/.test(ref??'')||ref===productionRef||!values.config||!values.report)
  throw new Error('Explicit non-production ref, private public-client config and report path required');
const settings=JSON.parse(await readFile(values.config,'utf8'));
const url=new URL(settings.url);
if(url.protocol!=='https:'||url.hostname!==`${ref}.supabase.co`||url.username||url.password||url.port||url.search||url.hash||url.pathname!=='/')
  throw new Error('Test endpoint mismatch');
if(!/^sb_publishable_[A-Za-z0-9_-]+$/.test(settings.publishableKey??'')) throw new Error('Publishable test key required');
const root=new URL('../../',import.meta.url);
const lines=(await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8')).trim().split(/\r?\n/);
const catalog=lines[1],partition='pleos-emulator';
const checks=[];
async function request(path,body,token,extraHeaders={}) {
  const response=await fetch(new URL(path,url),{method:body===undefined?'GET':'POST',
    headers:{apikey:settings.publishableKey,'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{}) ,...extraHeaders},
    ...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(30_000)});
  let data;try {data=await response.json();} catch {throw new Error(`Non-JSON reply for ${path}: ${response.status}`);}
  return {status:response.status,data};
}
async function rpc(name,body,token) {
  let reply=await request(`/rest/v1/rpc/${name}`,body,token);
  // A cache-miss 404 means no function executed; allow one schema-cache retry after setup's NOTIFY.
  if(reply.status===404&&reply.data?.code==='PGRST202') {
    await new Promise(r=>setTimeout(r,1000));reply=await request(`/rest/v1/rpc/${name}`,body,token);
  }
  assert.equal(reply.status,200,`${name}: HTTP ${reply.status} ${reply.data?.code??''}`);
  return reply.data;
}
async function check(name,action) {await action();checks.push(name);console.log(`PASS hosted: ${name}`);}
function body(extra={}) {return {p_partition:partition,p_catalog:catalog,...extra};}
let a,b;
await check('real anonymous Auth sessions are distinct and authenticated',async()=>{
  const first=await request('/auth/v1/signup',{data:{conquest_test:true}});
  const second=await request('/auth/v1/signup',{data:{conquest_test:true}});
  assert.equal(first.status,200,`First signup HTTP ${first.status} ${first.data?.code??''}`);
  assert.equal(second.status,200,`Second signup HTTP ${second.status} ${second.data?.code??''}`);
  a=first.data;b=second.data;
  assert.equal(a.user.is_anonymous,true);assert.equal(b.user.is_anonymous,true);
  assert.notEqual(a.user.id,b.user.id);assert.equal(typeof a.access_token,'string');assert.equal(typeof b.refresh_token,'string');
});
await check('real PostgREST returns no team without selecting one',async()=>{
  for(const session of [a,b]) {
    const result=await rpc('conquest_membership_v1',body(),session.access_token);
    assert.equal(result.status,'NO_TEAM');assert.equal(result.teamId,null);assert.equal(result.userId,session.user.id);
  }
});
await check('public-only caller and owner-spoof arguments cannot execute protected RPC',async()=>{
  const unauth=await request('/rest/v1/rpc/conquest_snapshot_v1',body());
  assert.ok([401,403].includes(unauth.status));
  const spoof=await request('/rest/v1/rpc/conquest_membership_v1',body({p_user_id:b.user.id}),a.access_token);
  assert.ok(spoof.status>=400);
});
await check('explicit teams are immutable and isolated between Auth accounts',async()=>{
  const red=await rpc('conquest_select_team_v1',body({p_team:'team-red'}),a.access_token);
  const blue=await rpc('conquest_select_team_v1',body({p_team:'team-blue'}),b.access_token);
  assert.equal(red.status,'SELECTED');assert.equal(blue.status,'SELECTED');
  const locked=await rpc('conquest_select_team_v1',body({p_team:'team-blue'}),a.access_token);
  assert.equal(locked.status,'TEAM_LOCKED');assert.equal(locked.teamId,'team-red');
  assert.equal(locked.selectedUtcMs,red.selectedUtcMs);
});
let before;
await check('real full snapshot has all app directions and caller-only summary',async()=>{
  before=await rpc('conquest_snapshot_v1',body(),a.access_token);
  assert.equal(before.protocol,1);assert.equal(before.roads.length,1453);
  assert.deepEqual(new Set(before.roads.map(r=>r.roadId)),new Set(lines.slice(2).map(l=>l.split('|')[0])));
  assert.equal(before.summary.teamId,'team-red');assert.equal(before.summary.contributionCount,0);
  assert.ok(!JSON.stringify(before).includes(b.user.id));
});
const fixtures=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8'));
const route=fixtures.find(f=>f.runtimeCatalog);
assert.ok(route&&route.expected.length===1);
const offset=route.samples.at(-1).time-route.samples[0].time;
assert.ok(offset<=45_000,'Live proof interval must fit a short bounded wait');
const nonce=randomUUID();
const samples=route.samples.map((s,i)=>({...s,id:`hosted-${nonce}-${i}`,time:before.generatedUtcMs+100+s.time-route.samples[0].time}));
const packet={eventId:`${samples.at(-1).id}:conquest:${route.expected[0]}`,roadId:route.expected[0],occurredMs:samples.at(-1).time,samples};
const raw=JSON.stringify(packet),digest=createHash('sha256').update(raw).digest('hex');
console.log(`Waiting ${offset+500}ms for the synthetic proof's server-based completion time (no GPS injection).`);
await new Promise(r=>setTimeout(r,offset+500));
await check('server verifies minimal road proof and exact immutable hash',async()=>{
  let result=await rpc('conquest_submit_v1',body({p_events:[raw]}),a.access_token);
  if(result.results[0].status==='FUTURE') {await new Promise(r=>setTimeout(r,1500));result=await rpc('conquest_submit_v1',body({p_events:[raw]}),a.access_token);}
  assert.equal(result.results[0].status,'ACCEPTED');assert.equal(result.results[0].requestHash,digest);
});
await check('lost-response replay and same-half-day duplicate do not inflate contribution',async()=>{
  assert.equal((await rpc('conquest_submit_v1',body({p_events:[raw]}),a.access_token)).results[0].status,'ACCEPTED');
  const duplicate=JSON.parse(raw);duplicate.samples.forEach(s=>s.id+='-duplicate');
  duplicate.eventId=`${duplicate.samples.at(-1).id}:conquest:${duplicate.roadId}`;
  const result=await rpc('conquest_submit_v1',body({p_events:[JSON.stringify(duplicate)]}),a.access_token);
  assert.equal(result.results[0].status,'DUPLICATE_HALF_DAY');
});
await check('other real account can contribute independently and summaries remain private',async()=>{
  assert.equal((await rpc('conquest_submit_v1',body({p_events:[raw]}),b.access_token)).results[0].status,'ACCEPTED');
  for(const [session,team,other] of [[a,'team-red',b],[b,'team-blue',a]]) {
    const result=await rpc('conquest_snapshot_v1',body(),session.access_token);
    assert.equal(result.userId,session.user.id);assert.equal(result.summary.teamId,team);assert.equal(result.summary.contributionCount,1);
    assert.ok(!JSON.stringify(result).includes(other.user.id));
    assert.equal(result.revision,before.revision); // No settlement-on-read or client-triggered settlement.
  }
});
await check('real refresh token preserves user identity and authorized snapshot access',async()=>{
  const refreshed=await request('/auth/v1/token?grant_type=refresh_token',{refresh_token:a.refresh_token});
  assert.equal(refreshed.status,200);assert.equal(refreshed.data.user.id,a.user.id);
  const snapshot=await rpc('conquest_snapshot_v1',body(),refreshed.data.access_token);
  assert.equal(snapshot.summary.contributionCount,1);
});
await check('private schema and reserved direct-write paths stay closed',async()=>{
  const privateRead=await request('/rest/v1/settlement_scopes',undefined,b.access_token,{'Accept-Profile':'conquest_internal'});
  assert.ok(privateRead.status>=400);
  const direct=await request('/rest/v1/conquest_ownership',{road_id:route.expected[0]},a.access_token);
  assert.ok(direct.status>=400);
});
await writeFile(values.report,JSON.stringify({projectRef:ref,executedAt:new Date().toISOString(),partition,catalog,
  anonymousAccounts:2,proofSamples:samples.length,checks},null,2)+'\n');
console.log(`PASS: ${checks.length} real hosted Auth/PostgREST checks. Session tokens are not logged or persisted; public key remains private input. Two devices and actual driving are not tested.`);
