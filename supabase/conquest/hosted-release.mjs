import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {parseArgs} from 'node:util';
import {resolve,join} from 'node:path';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {buildReleaseSql,isolateRelease} from './build-release.mjs';
import {buildTestStages} from './prepare-test-env.mjs';
import {snapshotSql,restoreSql,stateDigest} from './release-state.mjs';

const {values:v}=parseArgs({options:{'project-ref':{type:'string'},cli:{type:'string'},workdir:{type:'string'},out:{type:'string'},user:{type:'string'},run:{type:'boolean'}}});
const ref='hjqyiwqgukequtlrxgee';assert.equal(v['project-ref'],ref);assert.ok(v.run&&v.cli&&v.workdir&&v.out);
assert.match(v.user??'',/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/);
const out=resolve(v.out);await mkdir(out,{recursive:true});
const root=new URL('../../',import.meta.url),nonce=randomUUID().replaceAll('-','').slice(0,12);
const source=`conquest_release_${nonce}`,target=`conquest_release_${randomUUID().replaceAll('-','').slice(0,12)}`;
const jobName=`conquest-release-${nonce}`,partition='pleos-emulator';
const quote=s=>"'"+s.replaceAll("'","''")+"'";
const report={projectRef:ref,sourceSchema:source,restoreSchema:target,jobName,checks:[],startedAt:new Date().toISOString(),scope:'isolated logical conquest rehearsal; not project/Auth/Storage restore'};
const reportFile=join(out,`${source}-report.json`);let jobId;
function query(sql,{readOnly=false,file}={}) {
  return new Promise((accept,reject)=>{
    const child=spawn(resolve(v.cli),['--workdir',resolve(v.workdir),'db','query',`--remote=${ref}`,'--output-format','json',
      readOnly?'--read-only':'--yes',...(file?['--file',file]:[sql])],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let output='',error='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>error+=b);
    const timer=setTimeout(()=>{child.kill();reject(new Error('CLI timeout; inspect retained rehearsal artifacts'));},55_000);
    child.on('error',e=>{clearTimeout(timer);reject(e);});child.on('close',code=>{
      clearTimeout(timer);if(code!==0){reject(new Error(`CLI query failed (${code}); inspect private SQL, no credential/error-body logging`));return;}
      try {assert.ok((output+error).includes(`(${ref})`));accept(JSON.parse(output.slice(output.indexOf('{'))).rows);}
      catch {reject(new Error('Unexpected CLI output'));}
    });
  });
}
const one=async(sql,opts)=>(await query(sql,opts))[0]?.value;
async function fileQuery(name,sql) {const path=join(out,`${source}-${name}.sql`);await writeFile(path,sql);return query(null,{file:path});}
async function check(name,action) {await action();report.checks.push(name);console.log(`PASS hosted release: ${name}`);}
const baselineSql=`select jsonb_build_object('functions',(select jsonb_object_agg(proname,md5(prosrc)) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where n.nspname='public' and (proname like 'conquest_%' or proname='sync_exploration_v1')),
  'contributions',(select count(*) from conquest_internal.contributions),
  'scopes',(select jsonb_object_agg(partition,revision) from conquest_internal.settlement_scopes),
  'controls',(select jsonb_object_agg(partition,jsonb_build_array(paused,revision)) from conquest_internal.intake_control)) value`;
const history=()=>one(`select coalesce(jsonb_agg(jsonb_build_object('runid',runid,'status',status) order by runid),'[]'::jsonb) value
  from cron.job_run_details where jobid=${jobId}`,{readOnly:true});
const jobCommand=`select ${source}.settle_due('${partition}',1); select cron.alter_job(job_id := (select jobid from cron.job where jobname=${quote(jobName)}),active := false);`;
async function ownedJob() {
  const rows=await query(`select jobname,command,active from cron.job where jobid=${jobId}`,{readOnly:true});
  assert.equal(rows.length,1);assert.equal(rows[0].jobname,jobName);assert.equal(rows[0].command,jobCommand);return rows[0];
}
try {
  const marker=await one(`select exists(select 1 from conquest_test_control.environment where id=1 and project_ref=${quote(ref)}) value`,{readOnly:true});assert.equal(marker,true);
  const baseline=await one(baselineSql,{readOnly:true});
  const artifact=await buildReleaseSql();
  const migrations=(await readdir(new URL('supabase/migrations/',root))).filter(n=>/^\d{14}_conquest_server_v1\.sql$/.test(n));assert.equal(migrations.length,1);
  assert.equal((await readFile(new URL(`supabase/migrations/${migrations[0]}`,root),'utf8')).replaceAll('\r\n','\n'),artifact.sql);
  report.migration=migrations[0];report.migrationSha256=artifact.sha256;
  await check('reviewed migration compiles in a fresh closed hosted schema with no clock override',async()=>{
    await fileQuery('install',isolateRelease(artifact.sql,source)+'\nselect true value;');
    const count=await one(`select count(*)::int value from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where n.nspname=${quote(source)} and array_to_string(proargnames,',') like '%p_test_%'`,{readOnly:true});assert.equal(count,0);
  });
  const bundle=await buildTestStages(ref);
  await fileQuery('catalog',bundle.stages.find(s=>s.name.endsWith('conquest-catalog.sql')).sql.replaceAll('conquest_internal',source)+'\nselect true value;');
  const now=await one('select floor(extract(epoch from clock_timestamp())*1000)::bigint value',{readOnly:true});
  const noon=await one(`select ${source}.latest_noon_ms(${now}) value`);
  const fixture=JSON.parse(await readFile(new URL('docs/data/conquest-proof-fixtures.json',root),'utf8')).find(f=>f.runtimeCatalog);
  const samples=fixture.samples.map((s,i)=>({...s,id:`release-${nonce}-${i}`,time:noon-1000+s.time-fixture.samples.at(-1).time}));
  const raw=JSON.stringify({eventId:`${samples.at(-1).id}:conquest:${fixture.expected[0]}`,roadId:fixture.expected[0],occurredMs:noon-1000,samples});
  // Synthetic already-admitted history for missed-noon/restore testing, not a new late client submission.
  await fileQuery('history-fixture',`begin;
    select ${source}.initialize_settlement('pleos',${now}),${source}.initialize_settlement('${partition}',${now-4*86400000});
    select ${source}.select_team(${quote(v.user)},'${partition}','team-red',${now-4*86400000});
    insert into ${source}.receipts select ${quote(v.user)},'${partition}',r->>'eventId',
      jsonb_build_object('catalog',${quote(bundle.catalog)},'road',r->>'roadId','occurred_ms',(r->>'occurredMs')::bigint,
        'proof_hash',encode(sha256(convert_to((r->'samples')::text,'UTF8')),'hex'),'detector','directed-road-v1'),
      jsonb_build_object('eventId',r->>'eventId','status','ACCEPTED'),${noon-500} from (select ${quote(raw)}::jsonb r) input;
    insert into ${source}.gateway_receipts values(${quote(v.user)},'${partition}',${quote(JSON.parse(raw).eventId)},encode(sha256(convert_to(${quote(raw)},'UTF8')),'hex'));
    insert into ${source}.contributions values(${quote(v.user)},'${partition}',${quote(JSON.parse(raw).eventId)},${quote(bundle.catalog)},
      ${quote(fixture.expected[0])},(to_timestamp(${noon-1000}/1000.0) at time zone 'Asia/Seoul')::date,false,${noon-1000},'team-red');
    commit;select true value;`);
  await check('Cron creates inactive, then runs one bounded catch-up and stops itself',async()=>{
    await fileQuery('cron-access',await readFile(new URL('supabase/conquest/test-cron-admin.sql',root),'utf8'));
    const cron=await one("select jsonb_build_object('timezone',current_setting('cron.timezone',true),'version',(select extversion from pg_extension where extname='pg_cron')) value",{readOnly:true});
    assert.ok(cron.version);assert.ok(['GMT','UTC','Etc/UTC'].includes(cron.timezone));report.cron=cron;
    await query(`begin;do $$ declare id bigint;begin
      if exists(select 1 from cron.job where jobname=${quote(jobName)}) then raise exception 'Job collision';end if;
      id:=cron.schedule(${quote(jobName)},'* * * * *',${quote(jobCommand)});perform cron.alter_job(job_id:=id,active:=false);end $$;commit;`);
    jobId=await one(`select jobid value from cron.job where jobname=${quote(jobName)}`,{readOnly:true});report.jobId=jobId;
    assert.equal((await ownedJob()).active,false);assert.deepEqual(await history(),[]);
    await query(`select cron.alter_job(job_id:=${jobId},active:=true)`);
    console.log('Waiting for a real Cron minute tick; the rehearsal job deactivates itself after one successful run.');
    const deadline=Date.now()+90_000;let runs=[];
    while(Date.now()<deadline) {
      runs=await history();if(runs.some(r=>['failed','succeeded'].includes(r.status)))break;
      await new Promise(r=>setTimeout(r,3000));
    }
    assert.equal(runs.length,1);assert.equal(runs[0].status,'succeeded');assert.equal((await ownedJob()).active,false);
    assert.equal(await one(`select revision value from ${source}.settlement_scopes where partition='${partition}'`,{readOnly:true}),1);
    report.firstRun=runs[0];
  });
  await check('disabled job stays stopped across another minute while missed noons recover in order',async()=>{
    await ownedJob();await query(`select cron.alter_job(job_id:=${jobId},active:=false)`);
    const before=await history();
    const recovered=await one(`select ${source}.settle_due('${partition}',31) value`);
    assert.ok(recovered.processed>=2);assert.equal(recovered.hasMore,false);report.recovery=recovered;
    assert.equal((await one(`select ${source}.settle_due('${partition}') value`)).processed,0);
    const road=await one(`select owner value from ${source}.settlement_roads where partition='${partition}' and cutoff_ms=${noon} and road_id=${quote(fixture.expected[0])}`,{readOnly:true});assert.equal(road,'team-red');
    console.log('Observing the inactive job through another minute tick while preparing the logical backup.');
    await new Promise(r=>setTimeout(r,65_000));assert.deepEqual(await history(),before);
  });
  const data=await one(snapshotSql(source),{readOnly:true});
  const backup={format:'conquest-logical-v1',projectRef:ref,sourceSchema:source,migrationSha256:artifact.sha256,
    dataSha256:stateDigest(data),createdAt:new Date().toISOString(),data};
  const backupFile=join(out,`${source}-backup.json`);await writeFile(backupFile,JSON.stringify(backup));report.backupFile=backupFile;report.backupDataSha256=backup.dataSha256;
  const disk=JSON.parse(await readFile(backupFile,'utf8'));assert.equal(stateDigest(disk.data),backup.dataSha256);
  await check('logical backup restores into a second fresh schema with exact data and geometry equality',async()=>{
    await fileQuery('restore-install',isolateRelease(artifact.sql,target)+'\nselect true value;');
    await fileQuery('restore-data',restoreSql(target,disk.data));
    const restored=await one(snapshotSql(target),{readOnly:true});assert.deepEqual(restored,disk.data);
    assert.equal(stateDigest(restored),backup.dataSha256);assert.deepEqual(await one(snapshotSql(source),{readOnly:true}),data);
    report.restoredCounts=Object.fromEntries(Object.entries(restored).map(([n,rows])=>[n,rows.length]));
  });
  await check('restored receipt still replays original acceptance after deadline without a second award',async()=>{
    await query(`begin;select ${target}.set_intake('${partition}',false,0,'Isolated restore replay');
      select set_config('request.jwt.claim.sub',${quote(v.user)},true);
      do $$ declare reply jsonb;begin reply:=${target}.conquest_submit_v1('${partition}',${quote(bundle.catalog)},jsonb_build_array(${quote(raw)}::text));
      if reply#>>'{results,0,status}'<>'ACCEPTED' then raise exception 'Restored replay mismatch';end if;end $$;
      select ${target}.set_intake('${partition}',true,1,'Finish isolated restore replay');commit;`);
    assert.equal(await one(`select count(*)::int value from ${target}.contributions`,{readOnly:true}),1);
    assert.deepEqual(await one(baselineSql,{readOnly:true}),baseline);
    report.backupBytes=Buffer.byteLength(JSON.stringify(backup));
  });
  report.passed=true;
} finally {
  if(jobId!==undefined) {
    await ownedJob();await query(`select cron.unschedule(${jobId})`);
    report.jobRemoved=true;report.preservedJobHistory=await history();
  }
  report.finishedAt=new Date().toISOString();await writeFile(reportFile,JSON.stringify(report,null,2)+'\n');
}
