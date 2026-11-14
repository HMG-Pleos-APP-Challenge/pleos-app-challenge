import {PGlite} from '@electric-sql/pglite';
import {postgis} from '@electric-sql/pglite-postgis';
import assert from 'node:assert/strict';
import {buildTestStages,productionRef} from '../conquest/prepare-test-env.mjs';

const ref='abcdefghijklmnopqrst';
await assert.rejects(()=>buildTestStages(productionRef));
await assert.rejects(()=>buildTestStages('invalid'));
const bundle=await buildTestStages(ref);
const db=new PGlite({extensions:{postgis}});
try {
  await db.waitReady;
  await db.exec(`create role anon;create role authenticated;create schema auth;create table auth.users(id uuid primary key);
    create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;`);
  for(const stage of bundle.stages) await db.exec(stage.sql);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.roads')).rows[0].n,1453);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.road_geometry')).rows[0].n,1453);
  assert.deepEqual((await db.query('select id from conquest_internal.teams order by id')).rows.map(r=>r.id),['team-blue','team-red']);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.settlement_scopes where revision=0')).rows[0].n,2);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.memberships')).rows[0].n,0);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.intake_control where paused and revision=0')).rows[0].n,2);
  assert.equal((await db.query('select count(*)::int n from conquest_internal.intake_changes')).rows[0].n,2);
  const user='11111111-1111-4111-8111-111111111111';
  await db.query('insert into auth.users values($1)',[user]);
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user]);
  await db.exec('set role authenticated');
  const membership=(await db.query('select public.conquest_membership_v1($1,$2) result',['pleos-emulator',bundle.catalog])).rows[0].result;
  assert.equal(membership.status,'NO_TEAM');
  const snapshot=(await db.query('select public.conquest_snapshot_v1($1,$2) result',['pleos-emulator',bundle.catalog])).rows[0].result;
  assert.equal(snapshot.revision,0);assert.equal(snapshot.roads.length,1453);assert.equal(snapshot.summary.contributionCount,0);
  assert.equal((await db.query('select public.sync_exploration_v1($1,$2::jsonb) result',['pleos-emulator','[]'])).rows.length,1);
  await db.exec('reset role');
  await assert.rejects(()=>db.exec(bundle.stages[0].sql));await db.exec('rollback');
  console.log('PASS: production/malformed ref blocked; all staged SQL applied; 1453 geometries; app team IDs; neutral scopes; both intakes initially paused; own membership/snapshot and existing exploration API; repeat-install refusal.');
} finally {await db.close();}
