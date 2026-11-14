import {readFile,readdir,mkdir,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';

export const productionRef='qcbesfuohbvprpkwmlqu';
const root=new URL('../../',import.meta.url);
const quote=s=>"'"+s.replaceAll("'","''")+"'";
const hash=s=>createHash('sha256').update(s).digest('hex');

/** Builds files only: no network, credentials, link, reset, Cron or app activation. Fresh test project only. */
export async function buildTestStages(projectRef) {
  if(!/^[a-z]{20}$/.test(projectRef)||projectRef===productionRef) throw new Error('Explicit isolated project ref required');
  const asset=await readFile(new URL('app/src/main/assets/conquest/namyang.segments',root),'utf8');
  const [header,catalog,...lines]=asset.trim().split(/\r?\n/);
  if(header!=='PLEOS_CONQUEST_SEGMENTS_V1'||!catalog) throw new Error('Unknown conquest catalog');
  const roads=lines.map((line,ordinal)=>{
    const fields=line.split('|');if(fields.length!==5) throw new Error('Invalid road row');
    const coordinates=fields[4].split(';').map(p=>{
      const [lat,lon]=p.split(',').map(Number);
      if(!Number.isFinite(lat)||!Number.isFinite(lon)||Math.abs(lat)>80||Math.abs(lon)>180) throw new Error('Invalid geometry');
      return [lon,lat];
    });
    if(!fields[0]||coordinates.length<2) throw new Error('Invalid road');
    return {id:fields[0],ordinal,coordinates};
  });
  if(new Set(roads.map(r=>r.id)).size!==roads.length||roads.length!==1453) throw new Error('Unexpected runtime road set');
  const guard=`do $$ begin if not exists(select 1 from conquest_test_control.environment where id=1 and project_ref=${quote(projectRef)})
    then raise exception 'Wrong isolated test environment';end if;end $$;\n`;
  const stages=[{name:'00-isolated-marker.sql',sql:`begin;
    do $$ begin if to_regclass('public.explorer_profiles') is not null or to_regnamespace('conquest_internal') is not null
      or to_regnamespace('conquest_test_control') is not null then raise exception 'Fresh isolated project required';end if;end $$;
    create schema conquest_test_control;
    create table conquest_test_control.environment(id integer primary key check(id=1),project_ref text not null,created_at timestamptz not null default now());
    insert into conquest_test_control.environment(id,project_ref) values(1,${quote(projectRef)});
    alter table conquest_test_control.environment enable row level security;
    revoke all on schema conquest_test_control from public,anon,authenticated;
    revoke all on conquest_test_control.environment from public,anon,authenticated;
    commit;\n`}];
  let number=1;
  for(const source of (await readdir(new URL('supabase/sql-editor/',root))).filter(n=>n.endsWith('.sql')).sort())
    stages.push({name:`${String(number++).padStart(2,'0')}-existing-${source}`,sql:guard+await readFile(new URL(`supabase/sql-editor/${source}`,root),'utf8')});
  for(const source of ['ledger','proof','gateway','settlement','intake'])
    stages.push({name:`${String(number++).padStart(2,'0')}-conquest-${source}.sql`,sql:guard+await readFile(new URL(`supabase/conquest/${source}-prototype.sql`,root),'utf8')});
  let seed=guard+`begin;\ninsert into conquest_internal.teams values('team-red'),('team-blue');
    insert into conquest_internal.catalogs values(${quote(catalog)});
    insert into conquest_internal.roads select ${quote(catalog)},value from jsonb_array_elements_text(${quote(JSON.stringify(roads.map(r=>r.id)))}::jsonb);\n`;
  for(let i=0;i<roads.length;i+=100) seed+=`insert into conquest_internal.road_geometry
    select ${quote(catalog)},item->>'id',(item->>'ordinal')::integer,
      extensions.st_setsrid(extensions.st_geomfromgeojson(jsonb_build_object('type','LineString','coordinates',item->'coordinates')::text),4326)
    from jsonb_array_elements(${quote(JSON.stringify(roads.slice(i,i+100)))}::jsonb) item;\n`;
  seed+=`insert into conquest_internal.worlds values('pleos',${quote(catalog)}),('pleos-emulator',${quote(catalog)});\ncommit;\n`;
  stages.push({name:`${String(number++).padStart(2,'0')}-conquest-catalog.sql`,sql:seed});
  stages.push({name:`${String(number).padStart(2,'0')}-conquest-start.sql`,sql:guard+`begin;
    do $$ declare started bigint:=floor(extract(epoch from clock_timestamp())*1000)::bigint;
    begin perform conquest_internal.initialize_settlement('pleos',started);
      perform conquest_internal.initialize_settlement('pleos-emulator',started);end $$;
    notify pgrst,'reload schema';commit;\n`});
  return {projectRef,catalog,roads:roads.length,catalogSha256:hash(asset),stages};
}

export async function writeTestBundle(projectRef,directory) {
  const bundle=await buildTestStages(projectRef);
  await mkdir(directory,{recursive:true});
  for(const stage of bundle.stages) await writeFile(join(directory,stage.name),stage.sql);
  const manifest={projectRef,catalog:bundle.catalog,roads:bundle.roads,catalogSha256:bundle.catalogSha256,
    stages:bundle.stages.map(s=>({file:s.name,sha256:hash(s.sql),bytes:Buffer.byteLength(s.sql)}))};
  await writeFile(join(directory,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  return manifest;
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const {values}=parseArgs({options:{'project-ref':{type:'string'},out:{type:'string'}}});
  if(!values.out) throw new Error('--out is required; use a private directory outside Git');
  const manifest=await writeTestBundle(values['project-ref'],resolve(values.out));
  console.log(JSON.stringify({catalog:manifest.catalog,roads:manifest.roads,stages:manifest.stages.length}));
}
