import {writeFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import assert from 'node:assert/strict';
import {readRuntimeCatalog} from './runtime-catalog.mjs';

const quote=s=>"'"+s.replaceAll("'","''")+"'";
const hash=s=>createHash('sha256').update(s).digest('hex');
const tableNames=['teams','catalogs','roads','worlds','memberships','receipts','contributions','road_geometry','gateway_receipts','settlement_scopes','settlement_runs','settlement_roads'];
function schemaName(s) {assert.ok(s==='conquest_internal'||/^conquest_release_[a-f0-9]{12}$/.test(s),'Only canonical or isolated release schema');}

/** Generates files only. Runtime server time is captured once, after data is prepared; never resumes intake. */
export async function buildBootstrap({schema='conquest_internal',teams=['team-red','team-blue'],partitions=['pleos','pleos-emulator']}={}) {
  schemaName(schema);assert.ok(Array.isArray(teams)&&teams.length>=2&&teams.length<=64&&new Set(teams).size===teams.length);
  teams.forEach(t=>assert.match(t,/^[a-z][a-z0-9-]{0,99}$/));
  assert.ok(Array.isArray(partitions)&&partitions.length>0&&new Set(partitions).size===partitions.length);
  partitions.forEach(p=>assert.ok(['pleos','pleos-emulator'].includes(p)));
  const runtime=await readRuntimeCatalog(),catalog=runtime.catalog;
  const empty=tableNames.map(n=>`exists(select 1 from ${schema}.${n})`).join(' or ');
  let sql=`-- Closed initial data bootstrap. No migration history, Auth, legacy tables, job or client activation writes.
-- Catalog SHA256 ${runtime.sha256}; immutable version ${catalog}; ${runtime.roads.length} directions.
begin;
select pg_advisory_xact_lock(hashtextextended(${quote('conquest-bootstrap:'+schema)},0));
do $$ begin
  if to_regprocedure('${schema}.settle_due(text,integer)') is null or to_regprocedure('${schema}.submit_checked(text,text,jsonb)') is null
    or to_regprocedure('${schema}.settle_due(text,bigint,integer)') is not null then raise exception 'Reviewed release migration required';end if;
  if ${empty}
    or (select count(*) from ${schema}.intake_control)<>2
    or (select count(*) from ${schema}.intake_control where paused and revision=0)<>2
    or (select count(*) from ${schema}.intake_changes)<>2
    then raise exception 'Bootstrap requires fresh closed game state; inspect existing data instead of resetting';end if;
end $$;
insert into ${schema}.teams values ${teams.map(t=>`(${quote(t)})`).join(',')};
insert into ${schema}.catalogs values(${quote(catalog)});
insert into ${schema}.roads select ${quote(catalog)},value from jsonb_array_elements_text(${quote(JSON.stringify(runtime.roads.map(r=>r.id)))}::jsonb);
`;
  for(let i=0;i<runtime.roads.length;i+=100) sql+=`insert into ${schema}.road_geometry
    select ${quote(catalog)},item->>'id',(item->>'ordinal')::integer,
      extensions.st_setsrid(extensions.st_geomfromgeojson(jsonb_build_object('type','LineString','coordinates',item->'coordinates')::text),4326)
    from jsonb_array_elements(${quote(JSON.stringify(runtime.roads.slice(i,i+100)))}::jsonb) item;\n`;
  sql+=`insert into ${schema}.worlds values ${partitions.map(p=>`(${quote(p)},${quote(catalog)})`).join(',')};
do $$ declare started bigint:=floor(extract(epoch from clock_timestamp())*1000)::bigint; p text;
begin foreach p in array array[${partitions.map(quote).join(',')}] loop perform ${schema}.initialize_settlement(p,started);end loop;end $$;
commit;
`;
  // Verify exact directed geometry bytes and ordinals, not undirected ST_Equals.
  const expected=`select item->>'id' id,(item->>'ordinal')::integer ordinal,
    extensions.st_setsrid(extensions.st_geomfromgeojson(jsonb_build_object('type','LineString','coordinates',item->'coordinates')::text),4326) path
    from jsonb_array_elements(${quote(JSON.stringify(runtime.roads))}::jsonb) item`;
  const verifySql=`with expected as (${expected}), differences as (
    select coalesce(e.id,a.road_id) id from expected e full join ${schema}.road_geometry a on a.road_id=e.id and a.catalog=${quote(catalog)}
    where e.id is null or a.road_id is null or a.catalog<>${quote(catalog)} or e.ordinal<>a.ordinal
      or extensions.st_asewkb(e.path)<>extensions.st_asewkb(a.path)
  ) select jsonb_build_object('catalogVersion',${quote(catalog)},'assetSha256',${quote(runtime.sha256)},
    'geometryExact',not exists(select 1 from differences),
    'roadIdsExact',(select array_agg(id order by id) from ${schema}.roads where catalog=${quote(catalog)})=
      (select array_agg(id order by id) from expected),
    'teamsExact',(select array_agg(id order by id) from ${schema}.teams)=array[${[...teams].sort().map(quote).join(',')}],
    'catalogExact',(select count(*) from ${schema}.catalogs where version=${quote(catalog)})=1 and (select count(*) from ${schema}.catalogs)=1,
    'worldsExact',(select array_agg(partition order by partition) from ${schema}.worlds where catalog=${quote(catalog)})=array[${[...partitions].sort().map(quote).join(',')}]
      and (select count(*) from ${schema}.worlds)=${partitions.length},
    'scopes',(select jsonb_agg(to_jsonb(s) order by partition) from ${schema}.settlement_scopes s),
    'sameStart',(select count(distinct started_ms) from ${schema}.settlement_scopes)=1,
    'firstNoonExact',(select bool_and(first_ms>started_ms and first_ms-started_ms<=86400000
      and mod(first_ms+32400000,86400000)=43200000) from ${schema}.settlement_scopes),
    'initialNeutral',(select count(*) from ${schema}.settlement_runs)=0 and
      (select count(*) from ${schema}.settlement_scopes where revision=0 and last_ms is null and road_count=${runtime.roads.length})=${partitions.length},
    'noPlayerWrites',(select count(*) from ${schema}.memberships)=0 and (select count(*) from ${schema}.receipts)=0
      and (select count(*) from ${schema}.contributions)=0,
    'allPaused',(select bool_and(paused) from ${schema}.intake_control)) value;`;
  return {sql,verifySql,manifest:{schema,catalog,roads:runtime.roads.length,teams,partitions,catalogSha256:runtime.sha256,
    bootstrapSha256:hash(sql),verifySha256:hash(verifySql),startPolicy:'One server timestamp during bootstrap; first noon strictly after S',
    intake:'paused',networkExecuted:false}};
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  const {values:v}=parseArgs({options:{out:{type:'string'},schema:{type:'string'}}});assert.ok(v.out);
  const bundle=await buildBootstrap(v.schema?{schema:v.schema}:{}),out=resolve(v.out);await mkdir(out,{recursive:true});
  for(const [name,text] of [['bootstrap.sql',bundle.sql],['verify-bootstrap.sql',bundle.verifySql],['bootstrap-manifest.json',JSON.stringify(bundle.manifest,null,2)+'\n']])
    await writeFile(join(out,name),text);
  console.log(JSON.stringify(bundle.manifest));
}
