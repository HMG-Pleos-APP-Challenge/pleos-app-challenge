"""N2 offline junction-chain review. Produces proposals, never runtime assets.

Uses the pinned N1 topology and developer-only shapely==2.1.2.
Degree-two source way boundaries are traversed, not invented intersections.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from shapely.geometry import LineString
from audit_conquest_roads import MOTOR, boundary, project, review, sha


def analyze(source, region):
    nodes = {n["id"]: n for n in source["nodes"]}
    if len(nodes) != len(source["nodes"]):
        raise ValueError("Duplicate node ID")
    ways = {w["id"]: w for w in source["ways"]}
    if len(ways) != len(source["ways"]):
        raise ValueError("Duplicate way ID")
    neighbors, incident = defaultdict(set), defaultdict(list)
    edges, occurrences = {}, defaultdict(list)
    # Include motor-road side branches even when not conquest candidates.
    # Walking paths and geometric crossings without shared IDs are not junctions.
    for way in sorted(ways.values(), key=lambda w: w["id"]):
        refs = way["nodes"]
        if any(n not in nodes for n in refs):
            raise ValueError("Missing node reference")
        tags = way["tags"]
        if tags.get("highway") not in MOTOR or tags.get("area") == "yes":
            continue
        candidate = review(tags) == "candidate:topology_review_required"
        for index, (a, b) in enumerate(zip(refs, refs[1:])):
            if a == b:
                raise ValueError("Repeated adjacent node")
            neighbors[a].add(b)
            neighbors[b].add(a)
            key = (way["id"], index)
            occurrences[tuple(sorted((a, b)))].append(key)
            if candidate:
                edges[key] = (a, b)
                incident[a].append(key)
                incident[b].append(key)

    def stop(node):
        return len(neighbors[node]) != 2 or len(incident[node]) != 2

    unused = set(edges)
    chains = []
    def walk(start, edge):
        path, traversed = [start], []
        while edge in unused:
            unused.remove(edge)
            a, b = edges[edge]
            nxt = b if path[-1] == a else a
            traversed.append(edge)
            path.append(nxt)
            if stop(nxt) or nxt == start:
                break
            edge = next(e for e in incident[nxt] if e != edge)
        # Canonical orientation independent of file or traversal order.
        if tuple(reversed(path)) < tuple(path):
            path.reverse()
            traversed.reverse()
        chains.append((path, traversed))

    for node in sorted(incident):
        if stop(node):
            for edge in sorted(incident[node]):
                if edge in unused:
                    walk(node, edge)
    while unused:  # Components without junctions (closed loops).
        edge = min(unused)
        walk(min(edges[edge]), edge)

    results = []
    for path, traversed in sorted(chains):
        coords = [[nodes[n]["lat"], nodes[n]["lon"]] for n in path]
        line = LineString([project(lon, lat) for lat, lon in coords])
        if not line.intersects(region):
            continue
        reasons = []
        if not region.covers(line):
            reasons.append("boundary_crossing")
        if path[0] == path[-1]:
            reasons.append("closed_loop")
        if any(len(neighbors[n]) < 3 for n in (path[0], path[-1])):
            reasons.append("non_junction_endpoint")
        if line.length < 50:
            reasons.append("shorter_than_50m")
        if any(coords[i] == coords[i+1] for i in range(len(coords)-1)):
            reasons.append("zero_length_edge")
        if any(len(occurrences[tuple(sorted(edges[e]))]) > 1 for e in traversed):
            reasons.append("overlapping_source_edge")
        allowed = {"forward", "reverse"}
        for edge, a in zip(traversed, path):
            tag = ways[edge[0]]["tags"].get("oneway")
            same = edges[edge][0] == a
            if tag in {"yes", "1", "true", "-1"}:
                forward = same if tag != "-1" else not same
                allowed &= {"forward" if forward else "reverse"}
        if not allowed:
            reasons.append("inconsistent_direction")
        provenance = [[wid, ways[wid]["version"], index] for wid, index in traversed]
        digest = hashlib.sha256(json.dumps([path, provenance], separators=(",", ":")).encode()).hexdigest()
        names = list(dict.fromkeys(ways[e[0]]["tags"].get("name") for e in traversed))
        name = " / ".join(n for n in names if n) or "이름 없는 도로"
        directions = []
        for direction in sorted(allowed):
            seq = path if direction == "forward" else list(reversed(path))
            points = coords if direction == "forward" else list(reversed(coords))
            if len({e[0] for e in traversed}) == 1 and len(set(path)) == len(path):
                way = ways[traversed[0][0]]
                road_id = f"osm:way:{way['id']}:v{way['version']}:{seq[0]}:{seq[-1]}"
            else:
                road_id = f"osm:chain:{digest}:{seq[0]}:{seq[-1]}:{direction}"
            directions.append({"id": road_id, "fromNode": seq[0], "toNode": seq[-1], "points": points})
        results.append({"chainKey": digest, "nodes": path, "sourceEdges": provenance,
                        "name": name, "lengthMeters": round(line.length, 3),
                        "endpointDegrees": [len(neighbors[n]) for n in (path[0], path[-1])],
                        "reviewReasons": reasons, "directions": directions})
    ids = [d["id"] for row in results for d in row["directions"]]
    if len(ids) != len(set(ids)):
        raise ValueError("Ambiguous directed ID")
    return results


def old_mapping(path, rows):
    by_id = {d["id"]: (r, d) for r in rows for d in r["directions"]}
    mappings = []
    for line in path.read_text(encoding="utf-8-sig").splitlines()[2:]:
        if not line:
            continue
        road_id, _, _, _, text = line.split("|")
        points = [list(map(float, p.split(","))) for p in text.split(";")]
        match = by_id.get(road_id)
        status = "not_preserved_as_single_segment"
        if match:
            row, direction = match
            status = "identical" if points == direction["points"] else "geometry_changed"
            if row["reviewReasons"]:
                status += ":held"
        mappings.append({"oldId": road_id, "status": status})
    return mappings


def candidate_catalog(rows):
    """Serialize the reviewed conservative subset; activation is a separate task."""
    lines = ["PLEOS_CONQUEST_SEGMENTS_V1", "namyang-conquest-osm-20261108-full-v1"]
    for row in rows:
        if row["reviewReasons"]:
            continue
        name = row["name"].replace("|", " ").replace("\r", " ").replace("\n", " ")
        for direction in row["directions"]:
            coords = ";".join(f"{lat},{lon}" for lat, lon in direction["points"])
            lines.append(f"{direction['id']}|{name}|{direction['fromNode']}|{direction['toNode']}|{coords}")
    if len(lines) == 2:
        raise ValueError("No candidate directions")
    return "\n".join(lines)+"\n"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--source", type=Path, required=True)
    p.add_argument("--boundary", type=Path, required=True)
    p.add_argument("--legacy", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--candidate-catalog", type=Path, help="Optional offline catalog, after subset policy review")
    args = p.parse_args()
    if args.output.resolve() in {x.resolve() for x in (args.source, args.boundary, args.legacy)}:
        raise ValueError("Output must not overwrite input")
    if args.candidate_catalog and args.candidate_catalog.resolve() in {
            x.resolve() for x in (args.source, args.boundary, args.legacy, args.output)}:
        raise ValueError("Catalog must not overwrite input or report")
    source = json.loads(args.source.read_text(encoding="utf-8"))
    if source["schema"] != "CONQUEST_TOPOLOGY_SOURCE_V1":
        raise ValueError("Unsupported topology schema")
    region_id, region = boundary(args.boundary)
    if region_id != source["regionId"] or sha(args.boundary) != source["boundarySha256"]:
        raise ValueError("Boundary differs from N1 source")
    rows = analyze(source, region)
    clear = [r for r in rows if not r["reviewReasons"]]
    report = {"schema": "CONQUEST_SEGMENT_REVIEW_V1", "status": "proposal_not_runtime_catalog",
              "sourceSha256": sha(args.source), "boundarySha256": sha(args.boundary),
              "chainCount": len(rows), "unflaggedChainCount": len(clear),
              "unflaggedDirectionCount": sum(len(r["directions"]) for r in clear),
              "heldChainCount": len(rows)-len(clear),
              "overlappingReasonCounts": dict(sorted(Counter(reason for r in rows for reason in r["reviewReasons"]).items())),
              "legacyMapping": old_mapping(args.legacy, rows), "chains": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":"))+"\n", encoding="utf-8", newline="\n")
    if args.candidate_catalog:
        args.candidate_catalog.parent.mkdir(parents=True, exist_ok=True)
        args.candidate_catalog.write_text(candidate_catalog(rows), encoding="utf-8", newline="\n")
    print(json.dumps({k: v for k, v in report.items() if k != "chains"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
