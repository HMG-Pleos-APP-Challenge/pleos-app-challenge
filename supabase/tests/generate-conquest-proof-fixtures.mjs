// Shared input fixtures, not a detector implementation. Expectations are specified per scenario.
import {readFile,writeFile} from 'node:fs/promises';
const forward=[0,.2,.4,.6,.8,1,1.06];
const start=Date.parse('2026-11-13T12:01:00+09:00');
const sample=(i,f)=>({id:`e${i}`,time:start+i*2000,lat:37.2,lon:126.8+.002*f,accuracy:3});
const path=[[37.2,126.8],[37.2,126.802]];
const roads=[{id:'ab',points:path},{id:'ba',points:[...path].reverse()}];
const fixtures=[];
function add(name,samples,expected=[],overlap=false) {
  fixtures.push({name,roads:overlap?[...roads,{id:'another',points:path}]:roads,samples,expected});
}
const samples=()=>forward.map((f,i)=>sample(i,f));
add('complete-forward',samples(),['ab']);
add('complete-reverse',forward.map((f,i)=>sample(i,1-f)),['ba']);
add('middle-start',forward.slice(2).map((f,i)=>sample(i,f)));
add('exit-only',samples().slice(0,-1));
add('uturn',[0,.2,.4,.2,.4,.6,.8,1.06].map((f,i)=>sample(i,f)));
for(const [name,change] of [
  ['leave-road',s=>({...s,lat:s.lat+.01})],
  ['missing-accuracy',s=>({...s,accuracy:null})],
  ['poor-accuracy',s=>({...s,accuracy:50})],
  ['long-gap',s=>({...s,time:s.time+60000})],
]) add(name,samples().map((s,i)=>i===3?change(s):s));
add('exact-duplicates',samples().flatMap(s=>[s,s]),['ab']);
add('out-of-order',[sample(0,0),sample(2,.2),sample(1,.1),...forward.slice(2).map((f,i)=>sample(i+3,f))]);
add('ambiguous-overlap',samples(),[],true);
add('turn-after-exit',[...samples().slice(0,-1),{...sample(7,1),lat:37.2002}],['ab']);
add('turn-back-at-exit',[...samples().slice(0,-1),sample(7,.85)]);
add('accuracy-boundary',samples().map(s=>({...s,accuracy:10})),['ab']);
add('accuracy-over-boundary',samples().map(s=>({...s,accuracy:10.01})));
const bent=[[37.2,126.8],[37.2,126.801],[37.201,126.801]];
fixtures.push({name:'bent-polyline',roads:[{id:'bent-ab',points:bent},{id:'bent-ba',points:[...bent].reverse()}],
  samples:[[37.2,126.8],[37.2,126.8003],[37.2,126.8006],[37.2,126.8009],[37.2002,126.801],
    [37.2005,126.801],[37.2008,126.801],[37.201,126.801],[37.2012,126.801]].map(([lat,lon],i)=>({
      id:`b${i}`,time:start+i*2000,lat,lon,accuracy:3,
    })),expected:['bent-ab']});
const runtime=(await readFile(new URL('../../app/src/main/assets/conquest/namyang.segments',import.meta.url),'utf8')).trim().split(/\r?\n/);
const first=runtime[2].split('|');
const points=first[4].split(';').map(p=>p.split(',').map(Number));
if(points.length!==2) throw new Error('Update explicit runtime fixture after catalog change');
fixtures.push({name:'runtime-full-catalog',runtimeCatalog:true,samples:[0,.2,.4,.6,.8,1,1.15].map((f,i)=>({
  id:`r${i}`,time:start+i*2000,lat:points[0][0]+(points[1][0]-points[0][0])*f,
  lon:points[0][1]+(points[1][1]-points[0][1])*f,accuracy:3,
})),expected:[first[0]]});
await writeFile(new URL('../../docs/data/conquest-proof-fixtures.json',import.meta.url),JSON.stringify(fixtures,null,2)+'\n');
console.log(`Generated ${fixtures.length} shared proof fixtures`);
