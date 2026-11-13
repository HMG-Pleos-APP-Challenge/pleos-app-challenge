"""Offline N1 inventory, NOT a runtime catalog or approved eligibility policy.

Requires developer-only osmium==4.3.0 and shapely==2.1.2.
Retains complete highway ways touching a 250m boundary buffer for topology review.
Does not modify exploration assets, import exploration code, or generate game IDs.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

from shapely.geometry import LineString, MultiPolygon, Polygon
from shapely.ops import transform

PBF_SHA256 = "2539f6a674b9166acee836af1815558ab6264181372bbf1dbadafebb0accaa97"
PBF_URL = "https://download.geofabrik.de/asia/south-korea-261108.osm.pbf"
MOTOR = {"motorway", "motorway_link", "trunk", "trunk_link", "primary",
         "primary_link", "secondary", "secondary_link", "tertiary",
         "tertiary_link", "residential", "unclassified", "service", "living_street"}
ACCESS = ("access", "vehicle", "motor_vehicle", "motorcar")
KEYS = {"highway", "name", "ref", "oneway", "junction", "area", "bridge",
        "tunnel", "layer", "service", "lanes", "maxspeed", *ACCESS}


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def project(x, y, z=None):
    radius = 6371008.8
    return ((x - 126.8) * math.pi / 180 * radius * math.cos(math.radians(37.2)),
            (y - 37.2) * math.pi / 180 * radius)


def boundary(path):
    lines = Path(path).read_text(encoding="utf-8-sig").splitlines()
    if lines[0] != "PLEOS_BOUNDARY_V1":
        raise ValueError("Unsupported boundary")
    polygons = []
    for line in lines[4:]:
        if line == "POLYGON":
            rings = []
        elif line == "RING":
            ring = []
        elif line == "END_RING":
            rings.append(ring)
        elif line == "END_POLYGON":
            polygons.append(Polygon(rings[0], rings[1:]))
        else:
            ring.append(tuple(map(float, line.split(","))))
    result = transform(project, MultiPolygon(polygons))
    if result.is_empty or not result.is_valid:
        raise ValueError("Invalid boundary")
    return lines[2], result


def review(tags):
    """Conservative review buckets; never equates missing metadata with approval."""
    if tags.get("area") == "yes":
        return "exclude:area"
    if tags.get("highway") not in MOTOR:
        return "exclude:outside_motor_classes"
    if any(":conditional" in key or key.startswith("oneway:") for key in tags):
        return "hold:conditional_or_mode_direction"
    # Most specific motorcar permission overrides generic access.
    access = next((tags[k] for k in reversed(ACCESS) if k in tags), None)
    if access in {"no", "private", "agricultural", "forestry", "destination",
                  "delivery", "customers", "permit"}:
        return "exclude:restricted_access"
    if access not in {None, "yes", "permissive", "designated"}:
        return "hold:unknown_access"
    if tags.get("oneway") not in {None, "no", "yes", "1", "true", "-1", "0", "false"}:
        return "hold:unknown_direction"
    if tags.get("junction") in {"roundabout", "circular"}:
        return "hold:roundabout"
    if tags.get("highway") in {"motorway", "motorway_link", "trunk", "trunk_link"}:
        return "hold:high_speed_road"
    if tags.get("tunnel") not in {None, "no"}:
        return "hold:tunnel"
    if tags.get("highway") in {"service", "living_street"}:
        return "hold:additional_road_class"
    return "candidate:topology_review_required"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                               separators=(",", ":")) + "\n", encoding="utf-8")


def extract(pbf, region, roads):
    import osmium
    support = region.buffer(250)
    minx, miny, maxx, maxy = support.bounds
    class Collector(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.ways, self.nodes = [], {}
        def way(self, way):
            raw = dict(way.tags)
            if "highway" not in raw or len(way.nodes) < 2:
                return
            points = [(n.lon, n.lat) for n in way.nodes]
            # Reject distant ways by bounding box before allocating GEOS geometries.
            xy = [project(lon, lat) for lon, lat in points]
            if (max(x for x, _ in xy) < minx or min(x for x, _ in xy) > maxx or
                    max(y for _, y in xy) < miny or min(y for _, y in xy) > maxy):
                return
            line = LineString(xy)
            if not line.intersects(support):
                return
            tags = {k: v for k, v in raw.items()
                    if k in KEYS or k.startswith("oneway:") or ":conditional" in k}
            refs = [n.ref for n in way.nodes]
            for ref, (lon, lat) in zip(refs, points):
                self.nodes[ref] = {"id": ref, "lon": lon, "lat": lat}
            self.ways.append({"id": way.id, "version": way.version, "nodes": refs,
                              "tags": tags, "intersectsBoundary": line.intersects(region),
                              "fullyInsideBoundary": region.covers(line),
                              "lengthMeters": round(line.length, 3)})
    collector = Collector()
    collector.apply_file(str(pbf), locations=True, idx="sparse_mem_array")
    ways = sorted(collector.ways, key=lambda w: w["id"])
    original = json.loads(roads.read_text(encoding="utf-8"))
    by_id = {str(w["id"]): w for w in ways}
    comparison = []
    for feature in original["features"]:
        props = feature["properties"]
        way = by_id.get(str(props["osm_id"]))
        coordinates = None if way is None else [
            [collector.nodes[n]["lon"], collector.nodes[n]["lat"]] for n in way["nodes"]]
        comparison.append({"wayId": str(props["osm_id"]),
                           "found": way is not None,
                           "versionMatches": way is not None and way["version"] == props["osm_version"],
                           "geometryMatches": coordinates == feature["geometry"]["coordinates"]})
    nodes = [collector.nodes[n] for n in sorted(collector.nodes)]
    del collector
    return ways, nodes, comparison


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pbf", type=Path, required=True)
    parser.add_argument("--boundary", type=Path, required=True)
    parser.add_argument("--roads", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    inputs = {p.resolve() for p in (args.pbf, args.boundary, args.roads)}
    if args.output.resolve() in inputs or args.report.resolve() in inputs or args.output.resolve() == args.report.resolve():
        raise ValueError("Outputs must not overwrite inputs or each other")
    actual = sha(args.pbf)
    if actual != PBF_SHA256:
        raise ValueError("PBF differs from the pinned exploration source; do not mix snapshots")
    region_id, region = boundary(args.boundary)
    ways, nodes, comparison = extract(args.pbf, region, args.roads)
    inside = [w for w in ways if w["intersectsBoundary"]]
    rows = [{"wayId": w["id"], "review": review(w["tags"]),
             "crossesBoundary": not w["fullyInsideBoundary"],
             "closedWay": w["nodes"][0] == w["nodes"][-1],
             "lengthMeters": w["lengthMeters"]} for w in inside]
    mismatches = [row for row in comparison if not all(
        row[k] for k in ("found", "versionMatches", "geometryMatches"))]
    report = {"schema": "CONQUEST_SOURCE_AUDIT_V1", "regionId": region_id,
              "policyStatus": "proposal_not_runtime_eligibility",
              "pbfSha256": actual, "boundarySha256": sha(args.boundary),
              "explorationSourceSha256": sha(args.roads),
              "sourceUrl": PBF_URL, "supportBufferMeters": 250,
              "supportWayCount": len(ways) - len(inside), "intersectingWayCount": len(inside),
              "highwayCounts": dict(sorted(Counter(w["tags"]["highway"] for w in inside).items())),
              "reviewCounts": dict(sorted(Counter(r["review"] for r in rows).items())),
              "boundaryCrossingWayCount": sum(r["crossesBoundary"] for r in rows),
              "originalWayCount": len(comparison), "originalMismatches": mismatches,
              "ways": rows}
    source = {"schema": "CONQUEST_TOPOLOGY_SOURCE_V1", "regionId": region_id,
              "sourceUrl": PBF_URL, "sourceSha256": actual,
              "license": "ODbL-1.0", "attribution": "OpenStreetMap contributors",
              "boundarySha256": sha(args.boundary), "supportBufferMeters": 250,
              "ways": ways, "nodes": nodes}
    if mismatches:
        raise ValueError(f"Exploration source mismatch: {mismatches}")
    write_json(args.output, source)
    report["topologySha256"] = sha(args.output)
    write_json(args.report, report)
    print(json.dumps({k: v for k, v in report.items() if k != "ways"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
