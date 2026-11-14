import {spawn} from 'node:child_process';
import {writeFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {parseArgs,isDeepStrictEqual} from 'node:util';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
import {buildReleaseSql,isolateRelease} from './build-release.mjs';
import {buildBootstrap} from './build-bootstrap.mjs';

const {values:v}=parseArgs({options:{run:{type:'boolean'},'project-ref':{type:'string'},cli:{type:'string'},workdir:{type:'string'},out:{type:'string'}}});
const ref='hjqyiwqgukequtlrxgee';assert.equal(v['project-ref'],ref);assert.ok(v.run&&v.cli&&v.workdir&&v.out);
const out=resolve(v.out);await mkdir(out,{recursive:true});
const schema=`conquest_release_${randomUUID().replaceAll('-','').slice(0,12)}`;
const report={projectRef:ref,schema,startedAt:new Date().toISOString(),operatingWrites:false,checks:[]};
async function query(statement,{file,readOnly=false}={}) {
  return new Promise((accept,reject)=>{
    const child=spawn(resolve(v.cli),['--workdir',resolve(v.workdir),'db','query',`--remote=${ref}`,
      '--output-format','json',readOnly?'--read-only':'--yes',...(file?['--file',file]:[statement])],{windowsHide:true,stdio:['ignore','pipe','pipe']});
    let output='',error='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>error+=b);
    const timer=setTimeout(()=>{child.kill();reject(new Error('CLI timeout; inspect retained private files'));},55000);
    child.on('error',e=>{clearTimeout(timer);reject(e);});child.on('close',code=>{
      clearTimeout(timer);if(code!==0){reject(new Error(`CLI query failed (${code}); error body omitted`));return;}
      try {assert.ok((output+error).includes(ref));accept(JSON.parse(output.slice(output.indexOf('{'))).rows);}
      catch {reject(new Error('Unexpected CLI result'));}
    });
  });
}
async function fileQuery(name,sql,readOnly=false){const file=join(out,`${schema}-${name}.sql`);await writeFile(file,sql);return query(null,{file,readOnly});}
const baselineSql=`select jsonb_build_object('publicFunctions',(select jsonb_object_agg(proname,md5(prosrc)) from pg_proc p join pg_namespace n on n.oid=p.pronamespace
 where n.nspname='public' and (proname like 'conquest_%' or proname='sync_exploration_v1')),
 'contributions',(select count(*) from conquest_internal.contributions),
 'scopes',(select jsonb_agg(to_jsonb(s) order by partition) from conquest_internal.settlement_scopes s),
 'intake',(select jsonb_agg(to_jsonb(c) order by partition) from conquest_internal.intake_control c),
 'activeJobs',(select count(*) from cron.job where active)) value`;
const one=async sql=>(await query(sql,{readOnly:true}))[0].value;
try {
  assert.equal(await one(`select exists(select 1 from conquest_test_control.environment where id=1 and project_ref='${ref}') value`),true);
  const before=await one(baselineSql),release=await buildReleaseSql(),bundle=await buildBootstrap({schema});
  await fileQuery('migration',isolateRelease(release.sql,schema)+'\nselect true value;');report.checks.push('fresh isolated release installed');
  await fileQuery('bootstrap',bundle.sql+'\nselect true value;');
  const verified=(await fileQuery('verify',bundle.verifySql,true))[0].value;
  for(const [key,value] of Object.entries(verified)) if(!['scopes','assetSha256','catalogVersion'].includes(key)) assert.equal(value,true,key);
  report.verification=verified;report.manifest=bundle.manifest;report.checks.push('exact catalog, direction, teams, same server S, KST noon, neutral and paused');
  await assert.rejects(()=>fileQuery('repeat',bundle.sql));
  assert.ok(isDeepStrictEqual((await fileQuery('verify-again',bundle.verifySql,true))[0].value,verified));report.checks.push('repeat refused and S/data preserved');
  assert.deepEqual(await one(baselineSql),before);report.checks.push('existing public APIs, canonical state and active jobs unchanged');
  report.passed=true;console.log(JSON.stringify({schema,checks:report.checks,passed:true}));
} catch(error){report.passed=false;report.failure=error.message;process.exitCode=1;console.error(error.message);}
finally {report.finishedAt=new Date().toISOString();await writeFile(join(out,`${schema}-report.json`),JSON.stringify(report,null,2)+'\n');}
