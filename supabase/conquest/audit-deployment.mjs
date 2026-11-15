import {readFile,readdir,writeFile,mkdir} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs,isDeepStrictEqual} from 'node:util';
import {createHash} from 'node:crypto';
import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import assert from 'node:assert/strict';

const {values:v}=parseArgs({options:{'project-ref':{type:'string'},cli:{type:'string'},workdir:{type:'string'},out:{type:'string'}}});
const ref=v['project-ref'];assert.ok(['qcbesfuohbvprpkwmlqu','hjqyiwqgukequtlrxgee'].includes(ref));assert.ok(v.cli&&v.workdir&&v.out);
const root=new URL('../../',import.meta.url),sql=await readFile(new URL('supabase/conquest/deployment-audit.sql',root),'utf8');
const out=resolve(v.out);await mkdir(out,{recursive:true});
const canonical=x=>Array.isArray(x)?x.map(canonical):x&&typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])])):x;
const digest=x=>createHash('sha256').update(JSON.stringify(canonical(x))).digest('hex');
async function query(statement,file=false) {
  return new Promise((accept,reject)=>{
    // Intentionally no write/admin option and no migration list/repair call.
    const child=spawn(resolve(v.cli),['--workdir',resolve(v.workdir),'db','query',`--remote=${ref}`,'--read-only','--output-format','json',
      ...(file?['--file',fileURLToPath(new URL('supabase/conquest/deployment-audit.sql',root))]:[statement])],
      {windowsHide:true,stdio:['ignore','pipe','pipe']});
    let output='',error='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>error+=b);
    const timer=setTimeout(()=>{child.kill();reject(new Error('Read-only audit timeout'));},45_000);
    child.on('error',e=>{clearTimeout(timer);reject(e);});child.on('close',code=>{
      clearTimeout(timer);if(code!==0){reject(new Error('Read-only metadata query failed; error body omitted'));return;}
      try {const targetLine=(output+error).split(/\r?\n/).find(line=>line.startsWith('Target: project '));
        assert.ok(targetLine?.includes(ref));accept(JSON.parse(output.slice(output.indexOf('{'))).rows);} catch {reject(new Error('Invalid metadata result'));}
    });
  });
}
async function expected(files) {
  const db=new PGlite({extensions:{postgis}});
  try {
    await db.waitReady;await db.exec(`create role anon;create role authenticated;create schema auth;create table auth.users(id uuid primary key);
      create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
    const hashes={};
    for(const file of files) {const text=(await readFile(new URL(file,root),'utf8')).replaceAll('\r\n','\n');hashes[file]=createHash('sha256').update(text).digest('hex');await db.exec(text);}
    return {snapshot:(await db.query(sql)).rows[0].value,files:hashes};
  } finally {await db.close();}
}
const baselineFiles=(await readdir(new URL('supabase/migrations/',root))).filter(n=>/^20261110000[123]_/.test(n)).sort().map(n=>`supabase/migrations/${n}`);
assert.equal(baselineFiles.length,3);
const baseline=await expected(baselineFiles),editor=await expected(['supabase/sql-editor/00-schema.sql']);
const remote=(await query(sql,true))[0].value;
const status=(await query("select to_regclass('supabase_migrations.schema_migrations')::text history_table,to_regnamespace('conquest_internal')::text conquest_schema"))[0];
let versions=[];
if(status.history_table) versions=await query('select version from supabase_migrations.schema_migrations order by version');
function compare(left,right) {
  const differences={};
  for(const key of Object.keys(left)) if(!isDeepStrictEqual(left[key],right[key])) {
    const l=new Map((left[key]??[]).map(row=>[`${row.table??''}/${row.name??''}`,row]));
    const r=new Map((right[key]??[]).map(row=>[`${row.table??''}/${row.name??''}`,row]));
    differences[key]=[...new Set([...l.keys(),...r.keys()])].filter(k=>!isDeepStrictEqual(l.get(k),r.get(k)));
  }
  return differences;
}
const report={projectRef:ref,at:new Date().toISOString(),readOnly:true,historyTable:status.history_table,historyVersions:versions,
  conquestSchema:status.conquest_schema,baselineFiles:baseline.files,editorFiles:editor.files,
  baselineDifferences:compare(baseline.snapshot,remote),editorDifferences:compare(editor.snapshot,remote),
  localBaselinesAgree:Object.keys(compare(baseline.snapshot,editor.snapshot)).length===0,
  remoteSnapshotSha256:digest(remote),recommendation:'Do not db push/reset or repair history automatically; review exact differences and prepare the additive conquest file only.'};
await writeFile(join(out,`${ref}-schema.json`),JSON.stringify(remote,null,2)+'\n');
await writeFile(join(out,`${ref}-audit.json`),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
