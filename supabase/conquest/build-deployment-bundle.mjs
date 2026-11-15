import {readFile,writeFile,mkdir,readdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {parseArgs} from 'node:util';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {buildReleaseSql} from './build-release.mjs';
import {buildBootstrap} from './build-bootstrap.mjs';

const {values:v}=parseArgs({options:{audit:{type:'string'},out:{type:'string'}}});assert.ok(v.audit&&v.out);
const audit=JSON.parse(await readFile(resolve(v.audit),'utf8'));
assert.equal(audit.projectRef,'qcbesfuohbvprpkwmlqu');assert.equal(audit.readOnly,true);
assert.equal(audit.historyTable,null);assert.equal(audit.conquestSchema,null);assert.equal(audit.localBaselinesAgree,true);
assert.deepEqual(audit.baselineDifferences,{});assert.deepEqual(audit.editorDifferences,{});
const age=Date.now()-Date.parse(audit.at);assert.ok(age>=0&&age<86400000,'Refresh read-only audit within 24h');
const root=new URL('../../',import.meta.url),release=await buildReleaseSql(),bootstrap=await buildBootstrap();
const names=(await readdir(new URL('supabase/migrations/',root))).filter(n=>/^\d{14}_conquest_server_v1\.sql$/.test(n));assert.equal(names.length,1);
assert.equal((await readFile(new URL(`supabase/migrations/${names[0]}`,root),'utf8')).replaceAll('\r\n','\n'),release.sql);
const hash=s=>createHash('sha256').update(s).digest('hex');
for(const [file,expected] of Object.entries({...audit.baselineFiles,...audit.editorFiles}))
  assert.equal(hash((await readFile(new URL(file,root),'utf8')).replaceAll('\r\n','\n')),expected,'Audited baseline changed');
const files={[names[0]]:release.sql,'bootstrap.sql':bootstrap.sql,'verify-bootstrap.sql':bootstrap.verifySql,
  'operating-audit.json':JSON.stringify(audit,null,2)+'\n'};
const manifest={createdAt:new Date().toISOString(),mode:'files only; no DB write',projectRef:audit.projectRef,
  schemaFingerprint:audit.remoteSnapshotSha256,migrationHistory:'absent; no automatic repair or db push',
  bootstrap:bootstrap.manifest,files:Object.fromEntries(Object.entries(files).map(([n,s])=>[n,{sha256:hash(s),bytes:Buffer.byteLength(s)}])),
  holds:['Review current schema and exact object collisions immediately before use','Confirm project backup/restore and operator with partner',
    'Decide migration history baselining separately; never replay existing migrations blindly',
    'Apply only reviewed additive release, then bootstrap once; verify while paused',
    'S starts at bootstrap server time; do not execute far before agreed launch',
    'Cron installation, intake resume and app rollout are separate steps']};
const out=resolve(v.out);await mkdir(out,{recursive:true});
for(const [name,contents] of Object.entries(files))await writeFile(join(out,name),contents);
await writeFile(join(out,'deployment-manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log(JSON.stringify({files:Object.keys(files),operatingWrites:false,catalog:bootstrap.manifest.catalog,roads:1453}));
