import {readFile,writeFile} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {parseArgs} from 'node:util';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';

// Isolated operator + real HTTP test. Anonymous session/key remain in memory, never in the report.
const {values:v}=parseArgs({options:{'project-ref':{type:'string'},config:{type:'string'},cli:{type:'string'},
  workdir:{type:'string'},report:{type:'string'}}});
const ref='hjqyiwqgukequtlrxgee',partition='pleos-emulator';
assert.equal(v['project-ref'],ref);assert.ok(v.config&&v.cli&&v.workdir&&v.report);
const settings=JSON.parse(await readFile(v.config,'utf8'));
assert.equal(settings.url,`https://${ref}.supabase.co`);assert.match(settings.publishableKey,/^sb_publishable_[A-Za-z0-9_-]+$/);
const catalog=(await readFile(new URL('../../app/src/main/assets/conquest/namyang.segments',import.meta.url),'utf8')).split(/\r?\n/)[1];
const report={projectRef:ref,partition,checks:[],startedAt:new Date().toISOString()};
function query(sql,readOnly=false) {
  return new Promise((accept,reject)=>{
    const child=spawn(resolve(v.cli),['--workdir',resolve(v.workdir),'db','query',`--remote=${ref}`,
      '--output-format','json',readOnly?'--read-only':'--yes',sql],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let output='',error='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>error+=b);
    const timeout=setTimeout(()=>{child.kill();reject(new Error('Operator query timeout; inspect retained state'));},55_000);
    child.on('error',e=>{clearTimeout(timeout);reject(e);});child.on('close',code=>{
      clearTimeout(timeout);
      if(code!==0){reject(new Error('Operator SQL failed; error body omitted'));return;}
      try {assert.ok((output+error).includes(`(${ref})`));accept(JSON.parse(output.slice(output.indexOf('{'))).rows[0].value);}
      catch {reject(new Error('Invalid operator result'));}
    });
  });
}
// --read-only uses a restricted database role without private function EXECUTE.
// Status is a SELECT, but this operator-only function needs the explicit admin CLI context.
const state=()=>query(`select conquest_internal.intake_status('${partition}') value`);
let ownedRevision;
async function change(paused,expected) {
  const result=await query(`select conquest_internal.set_intake('${partition}',${paused},${expected},'Isolated hosted intake test') value`);
  ownedRevision=result.revision;return result;
}
async function request(path,body,token) {
  const response=await fetch(new URL(path,settings.url),{method:'POST',headers:{apikey:settings.publishableKey,
    'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`}:{})},body:JSON.stringify(body),signal:AbortSignal.timeout(30_000)});
  return {status:response.status,data:await response.json()};
}
const body=extra=>({p_partition:partition,p_catalog:catalog,...extra});
async function rpc(name,extra,token) {return request(`/rest/v1/rpc/conquest_${name}_v1`,body(extra),token);}
async function check(name,action) {await action();report.checks.push(name);console.log(`PASS hosted intake: ${name}`);}
const initial=await state();assert.equal(initial.paused,true,'Start explicitly paused; do not interrupt somebody else');
ownedRevision=initial.revision;
let session,raw,snapshot;
try {
  await check('real Auth and reads/team selection work while paused',async()=>{
    const signup=await request('/auth/v1/signup',{data:{conquest_intake_test:true}});
    assert.equal(signup.status,200);session=signup.data;
    const selected=await rpc('select_team',{p_team:'team-red'},session.access_token);
    assert.equal(selected.status,200);assert.equal(selected.data.teamId,'team-red');
    const read=await rpc('snapshot',{},session.access_token);assert.equal(read.status,200);snapshot=read.data;
    assert.equal(snapshot.roads.length,1453);assert.equal(snapshot.summary.contributionCount,0);
  });
  const fixture=JSON.parse(await readFile(new URL('../../docs/data/conquest-proof-fixtures.json',import.meta.url),'utf8')).find(f=>f.runtimeCatalog);
  const nonce=randomUUID(),offset=fixture.samples.at(-1).time-fixture.samples[0].time;
  assert.ok(offset>0&&offset<=45_000);
  const samples=fixture.samples.map((s,i)=>({...s,id:`intake-${nonce}-${i}`,time:snapshot.generatedUtcMs+100+s.time-fixture.samples[0].time}));
  raw=JSON.stringify({eventId:`${samples.at(-1).id}:conquest:${fixture.expected[0]}`,roadId:fixture.expected[0],occurredMs:samples.at(-1).time,samples});
  await new Promise(r=>setTimeout(r,offset+1000));
  await check('real PostgREST returns HTTP503/PT503 and awards nothing while paused',async()=>{
    const refused=await rpc('submit',{p_events:[raw]},session.access_token);
    assert.equal(refused.status,503);assert.equal(refused.data.code,'PT503');
    const read=await rpc('snapshot',{},session.access_token);assert.equal(read.status,200);assert.equal(read.data.summary.contributionCount,0);
  });
  await check('operator resume accepts the same immutable proof exactly once',async()=>{
    await change(false,ownedRevision);
    const accepted=await rpc('submit',{p_events:[raw]},session.access_token);
    assert.equal(accepted.status,200);assert.equal(accepted.data.results[0].status,'ACCEPTED');
  });
  await check('pause also withholds replay ACK; reads continue and stale operator cannot resume',async()=>{
    const stale=ownedRevision;await change(true,ownedRevision);
    assert.equal((await rpc('submit',{p_events:[raw]},session.access_token)).status,503);
    const read=await rpc('snapshot',{},session.access_token);assert.equal(read.status,200);assert.equal(read.data.summary.contributionCount,1);
    await assert.rejects(()=>change(false,stale));assert.equal((await state()).paused,true);
  });
  await check('second resume returns original result and a new event is half-day duplicate',async()=>{
    await change(false,ownedRevision);
    const replay=await rpc('submit',{p_events:[raw]},session.access_token);
    assert.equal(replay.status,200);assert.equal(replay.data.results[0].status,'ACCEPTED');
    const duplicate=JSON.parse(raw);duplicate.samples=duplicate.samples.map(s=>({...s,id:s.id+'-duplicate'}));
    duplicate.eventId=`${duplicate.samples.at(-1).id}:conquest:${duplicate.roadId}`;
    const result=await rpc('submit',{p_events:[JSON.stringify(duplicate)]},session.access_token);
    assert.equal(result.status,200);assert.equal(result.data.results[0].status,'DUPLICATE_HALF_DAY');
    assert.equal((await rpc('snapshot',{},session.access_token)).data.summary.contributionCount,1);
  });
  const quote=s=>"'"+s.replaceAll("'","''")+"'";
  assert.match(session.user.id,/^[a-f0-9-]{36}$/);
  const invoke=`select set_config('request.jwt.claim.sub',${quote(session.user.id)},true);
    do $intake_test$ declare reply jsonb;begin
      reply:=public.conquest_submit_v1('${partition}',${quote(catalog)},jsonb_build_array(${quote(raw)}::text));
      if reply#>>'{results,0,status}'<>'ACCEPTED' then raise exception 'Replay changed';end if;
    end $intake_test$;`;
  const blocked=`select set_config('request.jwt.claim.sub',${quote(session.user.id)},true);
    do $intake_test$ begin begin
      perform public.conquest_submit_v1('${partition}',${quote(catalog)},jsonb_build_array(${quote(raw)}::text));
      raise exception 'Expected paused submit';exception when sqlstate 'PT503' then null;end;end $intake_test$;`;
  async function waitFor(name,kind) {
    const until=Date.now()+9000;
    while(Date.now()<until) {
      const n=await query(`select count(*)::int value from pg_stat_activity where application_name=${quote(name)} and
        ${kind==='sleep'?"wait_event='PgSleep'":"wait_event_type='Lock'"}`,true);
      if(n===1)return;await new Promise(r=>setTimeout(r,150));
    }
    throw new Error('Actual intake lock barrier was not observed');
  }
  async function lockRace(first,second,firstChanges=false,secondChanges=false) {
    const name=`intake_${randomUUID().replaceAll('-','').slice(0,12)}`;
    const transaction=(suffix,sql,hold)=>`begin;set local statement_timeout='25s';set local application_name='${name}_${suffix}';
      ${sql} ${hold?'select pg_sleep(10);':''} commit;select conquest_internal.intake_status('${partition}') value`;
    const jobs=[];
    const start=(suffix,sql,hold,changes)=>{
      const job=query(transaction(suffix,sql,hold)).then(value=>{if(changes)ownedRevision=value.revision;return {value};},error=>({error}));
      jobs.push(job);
    };
    try {
      start('holder',first,true,firstChanges);await waitFor(`${name}_holder`,'sleep');
      start('waiter',second,false,secondChanges);await waitFor(`${name}_waiter`,'lock');
    } finally {for(const result of await Promise.all(jobs)) if(result.error)throw result.error;}
  }
  await check('real replay transaction holds the gate; operator pause waits for it to commit',async()=>{
    await lockRace(invoke,`select conquest_internal.set_intake('${partition}',true,${ownedRevision},'Isolated lock test');`,false,true);
    assert.equal((await state()).paused,true);
    assert.equal((await rpc('submit',{p_events:[raw]},session.access_token)).status,503);
  });
  await check('operator pause holds the gate; waiting submit sees committed pause and cannot ACK',async()=>{
    await change(false,ownedRevision);
    await lockRace(`select conquest_internal.set_intake('${partition}',true,${ownedRevision},'Isolated lock test');`,blocked,true,false);
    assert.equal((await state()).paused,true);
    assert.equal((await rpc('snapshot',{},session.access_token)).data.summary.contributionCount,1);
  });
  report.passed=true;
} finally {
  const current=await state();
  if(current.revision!==ownedRevision) report.cleanup='Operator revision changed; no automatic override';
  else if(!current.paused) await change(true,ownedRevision);
  report.finalControl=await state();report.finishedAt=new Date().toISOString();
  await writeFile(v.report,JSON.stringify(report,null,2)+'\n');
}
