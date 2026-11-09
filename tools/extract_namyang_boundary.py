"""Reproduce the bundled Namyang boundary using only the Python standard library.

Usage: python tools/extract_namyang_boundary.py --output <path>
Attribution and license: docs/data/LICENSE-DATA.txt.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

REVISION = "3bc00489b76696db864bdbea4b19985cdfa8b403"
URL = f"https://raw.githubusercontent.com/vuski/admdongkor/{REVISION}/ver20261001/HangJeongDong_ver20261001.geojson"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    with urlopen(URL, timeout=60) as response:
        source = json.load(response)
    matches = [f for f in source["features"] if f["properties"]["adm_cd"] == "31241723"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one Namyang region")
    feature = matches[0]
    if feature["geometry"]["type"] != "MultiPolygon":
        raise ValueError("Unexpected geometry")
    lines = ["PLEOS_BOUNDARY_V1", "sgis-admdongkor-20261001", "KR:SGIS:31241723", feature["properties"]["adm_nm"]]
    for polygon in feature["geometry"]["coordinates"]:
        lines.append("POLYGON")
        for ring in polygon:
            lines.append("RING")
            lines.extend(f"{lon},{lat}" for lon, lat in ring)
            lines.append("END_RING")
        lines.append("END_POLYGON")
    content = ("\n".join(lines) + "\n").encode("utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(content)
    print(hashlib.sha256(content).hexdigest())


if __name__ == "__main__":
    main()
