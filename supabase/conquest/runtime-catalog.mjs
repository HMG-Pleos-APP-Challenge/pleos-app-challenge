import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

/** One read boundary for the already-shared, immutable runtime conquest catalog. */
export async function readRuntimeCatalog() {
  const asset=await readFile(new URL('../../app/src/main/assets/conquest/namyang.segments',import.meta.url),'utf8');
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
  return {asset,catalog,roads,sha256:createHash('sha256').update(asset).digest('hex')};
}
