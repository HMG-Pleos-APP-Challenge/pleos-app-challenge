"""Build the prototype's reproducible Namyang road-cell catalog.

Developer-only dependencies: osmium 4.1.1, shapely 2.1.2.
Road data and generated road/cell databases: ODbL 1.0, OpenStreetMap contributors.
Boundary: SGIS / vuski/admdongkor, attribution in docs/data/LICENSE-DATA.txt.
"""
import argparse, json, math, hashlib
from pathlib import Path
from collections import defaultdict
from shapely.geometry import Polygon, MultiPolygon, LineString, box, mapping, shape
from shapely.ops import transform

RADIUS = 6371008.8
ORIGIN_LON, ORIGIN_LAT, SIZE = 126.8, 37.2, 500
XSCALE = math.radians(1) * RADIUS * math.cos(math.radians(ORIGIN_LAT))
YSCALE = math.radians(1) * RADIUS
VERSION = 'osm-geofabrik-20261108-namyang-grid500-v2'
ALLOWED = {'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
           'secondary','secondary_link','tertiary','tertiary_link','unclassified','residential'}
RESTRICTED = {'no','private','agricultural','forestry','destination','delivery','customers','permit'}

def project(x, y, z=None):
    return (x-ORIGIN_LON)*XSCALE, (y-ORIGIN_LAT)*YSCALE

def unproject(x, y):
    return ORIGIN_LON+x/XSCALE, ORIGIN_LAT+y/YSCALE

def read_boundary(path):
    lines = Path(path).read_text(encoding='utf-8-sig').splitlines()
    polygons, rings, ring = [], [], []
    for line in lines[4:]:
        if line == 'POLYGON': rings = []
        elif line == 'RING': ring = []
        elif line == 'END_RING': rings.append(ring)
        elif line == 'END_POLYGON': polygons.append(Polygon(rings[0], rings[1:]))
        else: ring.append(tuple(map(float,line.split(','))))
    geom = MultiPolygon(polygons)
    if not geom.is_valid: raise ValueError('Invalid source boundary')
    return lines[2], transform(project, geom)

def extract(pbf, boundary, output):
    import osmium
    class Roads(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.features=[]
        def way(self, way):
            tags = dict(way.tags)
            if tags.get('highway') not in ALLOWED: return
            if tags.get('area') == 'yes' or tags.get('tunnel') in {'yes','building_passage'}: return
            if any(tags.get(k) in RESTRICTED or f'{k}:conditional' in tags
                   for k in ('access','vehicle','motor_vehicle','motorcar')): return
            pts = [(n.lon,n.lat) for n in way.nodes]
            if len(pts)<2: return
            line = LineString(pts)
            projected = transform(project,line)
            if not projected.intersects(boundary): return
            self.features.append({'type':'Feature','properties':{'osm_id':str(way.id),
                'osm_version':way.version, **{k:v for k,v in tags.items() if k in
                    {'highway','access','motorcar','motor_vehicle','vehicle','tunnel','bridge','name'}}},
                'geometry':mapping(line)})
    roads=Roads()
    roads.apply_file(str(pbf), locations=True, idx='sparse_mem_array')
    features=sorted(roads.features,key=lambda f:int(f['properties']['osm_id']))
    result={'type':'FeatureCollection','datasetVersion':VERSION,
            'source':'OpenStreetMap contributors / Geofabrik south-korea-261108.osm.pbf',
            'license':'ODbL-1.0','features':features}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
    print('Extracted road ways:',len(features),flush=True)

def lines(geom):
    if geom.geom_type == 'LineString': yield geom
    elif hasattr(geom,'geoms'):
        for part in geom.geoms: yield from lines(part)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--boundary',type=Path,required=True)
    parser.add_argument('--roads',type=Path,required=True)
    parser.add_argument('--pbf',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    region_id,boundary=read_boundary(args.boundary)
    if args.pbf: extract(args.pbf,boundary,args.roads)
    raw=json.loads(args.roads.read_text(encoding='utf-8'))
    paths=[]
    road_interior=boundary.buffer(-25)
    for feature in raw['features']:
        props=feature['properties']
        # Validate local input as well as the original PBF extraction.
        if props.get('highway') not in ALLOWED: raise ValueError('Non-driving road in source extract')
        clipped=transform(project,shape(feature['geometry'])).intersection(road_interior)
        for index,line in enumerate(lines(clipped)):
            if line.length < 20: continue
            coords=[(round(x,3),round(y,3)) for x,y in line.coords]
            paths.append((f"osm:way:{props['osm_id']}:v{props['osm_version']}:p{index}",LineString(coords)))
    paths.sort(key=lambda p:p[0])
    cell_refs=defaultdict(set)
    demos=[]
    for i,(_,line) in enumerate(paths):
        minx,miny,maxx,maxy=line.bounds
        line_demos=[]
        for col in range(math.floor(minx/SIZE),math.floor(maxx/SIZE)+1):
            for row in range(math.floor(miny/SIZE),math.floor(maxy/SIZE)+1):
                # Exclude slivers/touches and leave room for a reliable 5m test fix.
                interior=box(col*SIZE+10,row*SIZE+10,(col+1)*SIZE-10,(row+1)*SIZE-10)
                pieces=list(lines(line.intersection(interior)))
                qualifying=[p for p in pieces if p.length>=20]
                if not qualifying: continue
                cell_refs[(col,row)].add(i)
                longest=max(qualifying,key=lambda p:p.length)
                a,b=longest.interpolate(.2,normalized=True),longest.interpolate(.8,normalized=True)
                if a.distance(b)>=8:
                    line_demos.append((line.project(a),col,row,a,b))
        if len(line_demos)>=4:
            ordered=sorted(line_demos)
            # A contiguous section of ONE source road is used; no disconnected-road teleport fixture.
            for start in range(len(ordered)-3):
                group=ordered[start:start+4]
                gaps=[abs(a[1]-b[1])+abs(a[2]-b[2]) for a,b in zip(group,group[1:])]
                if all(g<=2 for g in gaps):
                    demos.append((sum(gaps),i,group));break
    if not cell_refs or not demos: raise ValueError('No eligible cells / continuous demo road')
    output=['PLEOS_ROAD_CELLS_V1',VERSION,region_id,f'{ORIGIN_LON},{ORIGIN_LAT},{SIZE}']
    for road_id,line in paths:
        output.append('ROAD|'+road_id+'|'+';'.join(f'{x:.3f},{y:.3f}' for x,y in line.coords))
    for (col,row),refs in sorted(cell_refs.items()):
        output.append(f'CELL|{col}|{row}|'+','.join(map(str,sorted(refs))))
    _,demo_road,route=min(demos,key=lambda d:(d[0],d[1]))
    offset=0
    previous=None
    for i,(_,col,row,a,b) in enumerate(route):
        for n,p in enumerate((a,b)):
            if previous is not None:
                offset+=max(5000,math.ceil(previous.distance(p)/20)*1000)
            lon,lat=unproject(p.x,p.y)
            output.append(f'DEMO|cell-{i}-{n}|{lat:.9f}|{lon:.9f}|{offset}')
            previous=p
    args.output.parent.mkdir(parents=True,exist_ok=True)
    content=('\n'.join(output)+'\n').encode()
    args.output.write_bytes(content)
    metadata={'version':VERSION,'regionId':region_id,'sourceWayCount':len(raw['features']),
              'clippedPathCount':len(paths),'eligibleCellCount':len(cell_refs),'demoRoad':paths[demo_road][0],
              'sha256':hashlib.sha256(content).hexdigest(),'sourceSha256':hashlib.sha256(args.roads.read_bytes()).hexdigest()}
    args.output.with_suffix('.metadata.json').write_text(json.dumps(metadata,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(metadata),flush=True)

if __name__=='__main__':main()
