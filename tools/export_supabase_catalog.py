"""Generate versioned SQL from the exact bundled catalog, no network or third party packages.
Run: python tools/export_supabase_catalog.py
"""
from pathlib import Path
import math
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/exploration'
SX=math.pi/180*6371008.8*math.cos(math.radians(37.2));SY=math.pi/180*6371008.8
def quote(value):return "'"+str(value).replace("'","''")+"'"
def project(lon,lat):return f'{(float(lon)-126.8)*SX:.9f} {(float(lat)-37.2)*SY:.9f}'
def geom(wkt):return 'extensions.st_geomfromtext('+quote(wkt)+',0)'
rows=[]
def add(id,kind,region,version,area,roads,dwell,gap,move,xp):
 rows.append('('+','.join([quote(id),quote(kind),quote(region),quote(version),area,roads,str(dwell),str(gap),str(move),str(xp)])+')')
lines=(ASSETS/'namyang.boundary').read_text(encoding='utf-8').splitlines()
polygons=[];rings=[];points=[]
for line in lines[4:]:
 if line=='POLYGON':rings=[]
 elif line=='RING':points=[]
 elif line=='END_RING':
  if points[0]!=points[-1]:points.append(points[0])
  rings.append('('+','.join(points)+')')
 elif line=='END_POLYGON':polygons.append('('+','.join(rings)+')')
 elif ',' in line:points.append(project(*line.split(',')))
region=lines[2]
add(region,'region',region,lines[1],geom('MULTIPOLYGON('+','.join(polygons)+')'),'null',3000,30000,0,100)
lines=(ASSETS/'namyang.roadcells').read_text(encoding='utf-8').splitlines();version=lines[1];roads=[]
for line in lines[4:]:
 v=line.split('|')
 if v[0]=='ROAD':roads.append('('+v[2].replace(',',' ').replace(';',',')+')')
 elif v[0]=='CELL':
  col,row=int(v[1]),int(v[2]);x,y=col*100,row*100
  area=f'POLYGON(({x} {y},{x+100} {y},{x+100} {y+100},{x} {y+100},{x} {y}))'
  road='MULTILINESTRING('+','.join(roads[int(i)] for i in v[3].split(','))+')'
  add(f'{version}:{col}:{row}','cell',region,version,geom(area),geom(road),3000,30000,8,10)
lines=(ASSETS/'namyang.landmarks').read_text(encoding='utf-8').splitlines()
for line in lines[3:]:
 if not line:continue
 v=line.split('|');area='extensions.st_buffer('+geom('POINT('+project(v[3],v[2])+')')+','+v[4]+',64)'
 add(v[0],'landmark',region,lines[1],area,'null',int(v[5]),int(v[6]),0,200)
sql='-- Generated; source attribution remains in bundled asset documentation.\nbegin;\ninsert into public.exploration_targets(id,kind,region_id,dataset_version,area,roads,dwell_ms,max_gap_ms,min_move,xp) values\n'+',\n'.join(rows)+'\non conflict(id) do nothing;\ncommit;\n'
out=ROOT/'supabase/seed.sql';out.write_text(sql,encoding='utf-8');print(f'{len(rows)} targets -> {out}')
