"""Rebuild the ODbL landmark catalog from the included dated OSM point extract.

No Android dependency and no API key. Source/attribution: docs/data/LANDMARK-DATA-NOTICE.md.
"""
import argparse
import hashlib
import json
from pathlib import Path

VERSION = "osm-geofabrik-20261108-namyang-landmarks-v1-dwell120"
SELECTED_ID = "13851756178"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8-sig"))
    matches = [n for n in source["landmarks"] if n["id"] == SELECTED_ID]
    if len(matches) != 1:
        raise ValueError("Expected exactly one selected landmark")
    node = matches[0]
    if node["tags"].get("historic") != "memorial" or node["tags"].get("memorial") != "statue":
        raise ValueError("Unexpected source feature")
    content = "\n".join(["PLEOS_LANDMARK_CATALOG_V1", VERSION, "KR:SGIS:31241723",
        f"osm:node:{node['id']}|성모마리아 기념상|{node['lat']}|{node['lon']}|100.0|120000|15000|https://www.openstreetmap.org/node/{node['id']}"]) + "\n"
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(content.encode("utf-8"))
    print(hashlib.sha256(content.encode("utf-8")).hexdigest())


if __name__ == "__main__":
    main()
