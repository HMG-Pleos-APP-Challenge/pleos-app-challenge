import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';

const normal=['teams','catalogs','roads','worlds','memberships','receipts','contributions','gateway_receipts'];
const names=[...normal,'road_geometry','settlement_scopes','settlement_runs','settlement_roads','intake_control','intake_changes'];
const quote=s=>"'"+s.replaceAll("'","''")+"'";
const schemaName=s=>assert.match(s,/^conquest_release_[a-f0-9]{12}$/);
export const stateDigest=data=>createHash('sha256').update(JSON.stringify(data)).digest('hex');

/** Test rehearsal only: fixed table allowlist; public catalog geometry is EWKB, not driver proof. */
export function snapshotSql(schema) {
  schemaName(schema);
  const pairs=names.map(name=>{
    const row=name==='road_geometry'?"(to_jsonb(t)-'path')||jsonb_build_object('path_ewkb',encode(extensions.st_asewkb(t.path),'hex'))":'to_jsonb(t)';
    return `${quote(name)},(select coalesce(jsonb_agg(${row} order by to_jsonb(t)::text),'[]'::jsonb) from ${schema}.${name} t)`;
  });
  return `select jsonb_build_object(${pairs.join(',\n')}) value`;
}

/** Only a new release rehearsal schema; never an in-place merge, reset or operating restore. */
export function restoreSql(schema,data) {
  schemaName(schema);assert.deepEqual(Object.keys(data).sort(),[...names].sort());
  names.forEach(n=>assert.ok(Array.isArray(data[n])));
  assert.deepEqual(data.intake_control.map(r=>r.partition).sort(),['pleos','pleos-emulator']);
  assert.ok(data.intake_control.every(r=>r.paused===true),'Pause before exporting');
  const json=name=>`${quote(JSON.stringify(data[name]))}::jsonb`;
  const rows=name=>`jsonb_populate_recordset(null::${schema}.${name},${json(name)})`;
  const empty=names.filter(n=>!['intake_control','intake_changes'].includes(n))
    .map(n=>`exists(select 1 from ${schema}.${n})`).join(' or ');
  let sql=`begin;
do $$ begin if ${empty}
  or (select count(*) from ${schema}.intake_control where paused and revision=0)<>2
  or (select count(*) from ${schema}.intake_changes)<>2
  then raise exception 'Fresh closed rehearsal target required';end if;end $$;\n`;
  for(const n of normal) sql+=`insert into ${schema}.${n} select * from ${rows(n)};\n`;
  sql+=`insert into ${schema}.road_geometry
    select t->>'catalog',t->>'road_id',(t->>'ordinal')::integer,extensions.st_geomfromewkb(decode(t->>'path_ewkb','hex'))
    from jsonb_array_elements(${json('road_geometry')}) t;
insert into ${schema}.settlement_scopes(partition,catalog,started_ms,first_ms,road_count)
  select partition,catalog,started_ms,first_ms,road_count from ${rows('settlement_scopes')};
insert into ${schema}.settlement_runs select * from ${rows('settlement_runs')};
insert into ${schema}.settlement_roads select * from ${rows('settlement_roads')};
update ${schema}.settlement_scopes c set last_ms=s.last_ms,revision=s.revision
  from ${rows('settlement_scopes')} s where c.partition=s.partition;
update ${schema}.intake_control c set paused=s.paused,revision=s.revision,changed_ms=s.changed_ms,reason=s.reason,changed_by=s.changed_by
  from ${rows('intake_control')} s where c.partition=s.partition;
insert into ${schema}.intake_changes select * from ${rows('intake_changes')}
  on conflict(partition,revision) do update set paused=excluded.paused,changed_ms=excluded.changed_ms,
    reason=excluded.reason,changed_by=excluded.changed_by;
commit;
select true value;`;
  return sql;
}
