import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createHash,randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {parseArgs} from 'node:util';
import assert from 'node:assert/strict';

const isolatedRef='hjqyiwqgukequtlrxgee';
const root=new URL('../../',import.meta.url);
const day=86400000,cutoff=Date.parse('2026-11-10T12:00:00+09:00'),start=cutoff-day;
const quote=s=>"'"+s.replaceAll("'","''")+"'";
const digest=s=>createHash('sha256').update(s).digest('hex');

/** Copy only our SQL into a new closed schema. Never rewrite the installed conquest schema/functions. */
export async function buildConcurrencySql(ref,schema,users) {
  assert.equal(ref,isolatedRef,'Only the known isolated project is allowed');
  assert.match(schema,/^conquest_race_[a-f0-9]{12}$/);
  assert.equal(users.length,2);assert.notEqual(users[0],users[1]);
  users.forEach(u=>assert.match(u,/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/));
  const sources={};
  for(const name of ['ledger','settlement']) sources[name]=await readFile(new URL(`supabase/conquest/${name}-prototype.sql`,root),'utf8');
  const renamed=name=>sources[name].replaceAll('conquest_internal',schema)
    .replaceAll('public.conquest_snapshot_v1',`${schema}.conquest_snapshot_v1`);
  const settlement=renamed('settlement').replace(`grant execute on function ${schema}.conquest_snapshot_v1(text,text) to authenticated;`,'');
  assert.ok(!settlement.includes('public.') && !renamed('ledger').includes('public.'));
  const guard=`do $$ begin if not exists(select 1 from conquest_test_control.environment where id=1 and project_ref=${quote(ref)})
    then raise exception 'Wrong isolated environment';end if;end $$;`;
  const sql=`${guard}
${renamed('ledger')}
create table ${schema}.fixture_clock(ms bigint not null);
insert into ${schema}.fixture_clock values(${cutoff+day+100});
create function ${schema}.admission_now_ms() returns bigint language sql volatile set search_path=''
  as $$ select ms from ${schema}.fixture_clock $$;
${settlement}
begin;
create table ${schema}.probe_results(id text primary key,value jsonb not null);
alter table ${schema}.fixture_clock enable row level security;
alter table ${schema}.probe_results enable row level security;
insert into ${schema}.teams values('red'),('blue');
insert into ${schema}.catalogs values('race-fixture');
insert into ${schema}.roads values('race-fixture','ab'),('race-fixture','ba');
insert into ${schema}.worlds values('pleos','race-fixture'),('pleos-emulator','race-fixture');
select ${schema}.initialize_settlement('pleos',${start}),${schema}.initialize_settlement('pleos-emulator',${start});
select ${schema}.select_team(${quote(users[0])},'pleos','red',${start-1}),
  ${schema}.select_team(${quote(users[1])},'pleos','blue',${start-1}),
  ${schema}.select_team(${quote(users[0])},'pleos-emulator','red',${start-1});
revoke all on schema ${schema} from public,anon,authenticated;
revoke all on all tables in schema ${schema} from public,anon,authenticated;
revoke all on all functions in schema ${schema} from public,anon,authenticated;
commit;
select ${quote(schema)} as ready;
`;
  return {sql,sourceHashes:Object.fromEntries(Object.entries(sources).map(([n,s])=>[n,digest(s)]))};
}

async function main() {
  const {values:v}=parseArgs({options:{'project-ref':{type:'string'},cli:{type:'string'},workdir:{type:'string'},
    out:{type:'string'},users:{type:'string'},run:{type:'boolean'}}});
  assert.equal(v['project-ref'],isolatedRef);assert.ok(v.run&&v.cli&&v.workdir&&v.out&&v.users,'Explicit --run and paths/users required');
  const users=v.users.split(','),schema=`conquest_race_${randomUUID().replaceAll('-','').slice(0,12)}`;
  const out=resolve(v.out);await mkdir(out,{recursive:true});
  const bundle=await buildConcurrencySql(v['project-ref'],schema,users);
  const setup=join(out,`${schema}.sql`);await writeFile(setup,bundle.sql);
  const report={projectRef:isolatedRef,schema,sourceHashes:bundle.sourceHashes,controlledCutoffMs:cutoff,
    realWallClockNoon:false,checks:[],startedAt:new Date().toISOString()};
  const reportFile=join(out,`${schema}.json`);
  let sequence=0;
  function query(sql,{readOnly=false,file}={}) {
    const args=['--workdir',resolve(v.workdir),'db','query',`--remote=${isolatedRef}`,'--output-format','json',
      ...(readOnly?['--read-only']:['--yes']),...(file?['--file',file]:[sql])];
    return new Promise((accept,reject)=>{
      const child=spawn(resolve(v.cli),args,{windowsHide:true,stdio:['ignore','pipe','pipe']});
      let output='',error='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>error+=b);
      const timeout=setTimeout(()=>{child.kill();reject(new Error('CLI query timeout; inspect retained schema before retrying'));},55_000);
      child.on('error',e=>{clearTimeout(timeout);reject(e);});
      child.on('exit',code=>{
        clearTimeout(timeout);
        if(code!==0) {reject(new Error(`CLI query failed (${code}); no credential/error-body logging`));return;}
        try {assert.ok((output+error).includes(`(${isolatedRef})`));const parsed=JSON.parse(output.slice(output.indexOf('{')));
          assert.ok(Array.isArray(parsed.rows));accept(parsed.rows);}
        catch {reject(new Error('Unexpected CLI query output'));}
      });
    });
  }
  const one=async(sql,options={})=>(await query(sql,options))[0];
  const baselineSql=`select jsonb_build_object('publicFunctions',(select jsonb_object_agg(proname,md5(prosrc)) from pg_proc p
    join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and (proname like 'conquest_%' or proname='sync_exploration_v1')),
    'contributions',(select count(*) from conquest_internal.contributions),
    'scopes',(select jsonb_object_agg(partition,revision) from conquest_internal.settlement_scopes)) value`;
  async function check(name,action) {await action();report.checks.push(name);console.log(`PASS PostgreSQL concurrency: ${name}`);}
  const accept=(id,partition='pleos',occurred=cutoff-1,received=cutoff-1)=>`${schema}.accept_verified(${quote(users[0])},${quote(partition)},
    'race-fixture',${quote(id)},'ab',${occurred},${quote('a'.repeat(64))},'race-fixture',${received})`;
  const settle=(partition='pleos',at=cutoff)=>`${schema}.settle_due(${quote(partition)},${at},7)`;
  const result=id=>one(`select value from ${schema}.probe_results where id=${quote(id)}`,{readOnly:true}).then(r=>r.value);
  const transaction=(name,id,expression,hold)=>`begin;set local statement_timeout='25s';set local application_name=${quote(name)};
    insert into ${schema}.probe_results values(${quote(id)},${expression});${hold?'select pg_sleep(10);':''}
    commit;select value from ${schema}.probe_results where id=${quote(id)}`;
  const waitFor=async(name,kind)=>{
    const until=Date.now()+9000;
    while(Date.now()<until) {
      const r=await one(`select count(*)::int n from pg_stat_activity where application_name=${quote(name)} and
        ${kind==='sleep'?"wait_event='PgSleep'":"wait_event_type='Lock'"}`,{readOnly:true});
      if(r.n===1)return;await new Promise(r=>setTimeout(r,150));
    }
    throw new Error(`Barrier not observed: ${kind}; do not claim a concurrency pass`);
  };
  async function race(leftId,leftExpression,rightId,rightExpression) {
    const prefix=`${schema}_${++sequence}`,leftName=`${prefix}_l`,rightName=`${prefix}_r`;
    const jobs=[];const startJob=sql=>{const p=query(sql).then(value=>({value}),error=>({error}));jobs.push(p);};
    try {
      startJob(transaction(leftName,leftId,leftExpression,true));await waitFor(leftName,'sleep');
      startJob(transaction(rightName,rightId,rightExpression,false));await waitFor(rightName,'lock');
    } finally {for(const finished of await Promise.all(jobs)) if(finished.error)throw finished.error;}
  }
  try {
    const baseline=await one(baselineSql,{readOnly:true});
    await query(null,{file:setup});
    await check('submit holds world lock; settlement waits and includes committed contribution',async()=>{
      await race('first-submit',accept('first-submit'),'first-settle',settle());
      assert.equal((await result('first-submit')).status,'ACCEPTED');assert.equal((await result('first-settle')).processed,1);
      const r=await one(`select owner,scores from ${schema}.settlement_roads where partition='pleos' and road_id='ab'`,{readOnly:true});
      assert.equal(r.owner,'red');assert.deepEqual(r.scores,{red:1});
    });
    await check('settlement holds world lock; first late submit waits and is rejected',async()=>{
      await race('other-settle',settle('pleos-emulator'),'late-submit',accept('late-submit','pleos-emulator',cutoff-1,cutoff+1));
      assert.equal((await result('late-submit')).status,'LATE');
      assert.equal((await one(`select count(*)::int n from ${schema}.contributions where partition='pleos-emulator'`,{readOnly:true})).n,0);
    });
    await check('accepted replay after cutoff returns original result; fresh late event is rejected',async()=>{
      assert.equal((await one(`select ${accept('first-submit','pleos',cutoff-1,cutoff+1)} value`)).value.status,'ACCEPTED');
      assert.equal((await one(`select ${accept('new-late','pleos',cutoff-1,cutoff+1)} value`)).value.status,'LATE');
    });
    await check('same-account simultaneous events serialize with one half-day award',async()=>{
      await race('afternoon-a',accept('afternoon-a','pleos',cutoff+1,cutoff+2),
        'afternoon-b',accept('afternoon-b','pleos',cutoff+1,cutoff+2));
      assert.equal((await result('afternoon-a')).status,'ACCEPTED');
      assert.equal((await result('afternoon-b')).status,'DUPLICATE_HALF_DAY');
      assert.equal((await one(`select count(*)::int n from ${schema}.contributions where partition='pleos'`,{readOnly:true})).n,2);
    });
    await check('two settlement workers serialize and settle the same noon once',async()=>{
      await race('next-settle-a',settle('pleos',cutoff+day),'next-settle-b',settle('pleos',cutoff+day));
      assert.equal((await result('next-settle-a')).processed,1);assert.equal((await result('next-settle-b')).processed,0);
      assert.equal((await one(`select count(*)::int n from ${schema}.settlement_runs where partition='pleos'`,{readOnly:true})).n,2);
    });
    await check('test clone stays closed; original public functions and live conquest state unchanged',async()=>{
      assert.deepEqual(await one(baselineSql,{readOnly:true}),baseline);
      const r=await one(`select has_schema_privilege('anon',${quote(schema)},'USAGE') anon,
        has_schema_privilege('authenticated',${quote(schema)},'USAGE') authenticated,
        (select bool_and(relrowsecurity) from pg_class c join pg_namespace n on n.oid=c.relnamespace
          where n.nspname=${quote(schema)} and c.relkind='r') rls`,{readOnly:true});
      assert.equal(r.anon,false);assert.equal(r.authenticated,false);assert.equal(r.rls,true);
    });
    report.passed=true;
  } finally {report.finishedAt=new Date().toISOString();await writeFile(reportFile,JSON.stringify(report,null,2)+'\n');}
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) await main();
