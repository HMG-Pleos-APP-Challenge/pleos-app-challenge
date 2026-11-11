# Namyang conquest road data

© OpenStreetMap contributors. ODbL 1.0. Preserve `LICENSE-CONQUEST-OSM.txt` and
https://www.openstreetmap.org/copyright with the app and derived data.

The exploration branch `f18f6e1` identified candidate roads in
`docs/data/namyang-osm-roads.geojson`. Its way 55055096 version 24 is reused here.
No exploration code or runtime asset was moved or changed.

Missing junction/direction metadata was retrieved read-only on 2026-11-10:
https://api.openstreetmap.org/api/0.6/map.json?bbox=126.803,37.204,126.834,37.220

`namyang-conquest-source.json` retains road nodes, way versions, tags, and the
hash of the original response. Non-road objects and editor metadata are removed.
Candidate roads must also occur in the exploration source. Neighbor roads provide
node adjacency to identify real shared-node junctions (at least three neighbors).
Geometrically crossing lines without a shared node are not treated as junctions.

`tools/build_conquest_catalog.ps1` selects three 100–450 m junction-to-junction
sections. The runtime asset is `assets/conquest/namyang.segments` with version
`namyang-conquest-osm-20261110-v1`. Directional IDs include way version and endpoint nodes.
The source contains only a small test area; it is not the whole Namyang road network.

These selected ordinary tertiary roads have no oneway restriction in this snapshot;
two directions are generated using OSM default bidirectionality. Explicit yes/1/true
and -1 are supported by the builder; unknown/conditional directions and roundabouts
are excluded. The catalog describes OSM data, not independently field-verified road law.
OSM semantics: https://wiki.openstreetmap.org/wiki/Key:oneway
API: https://wiki.openstreetmap.org/wiki/API_v0.6

Changes: retain public motor-road topology, split at shared-node junctions, select a
small subset, generate directed polylines. Keep the source, builder, runtime data,
and attribution available with distribution. The application contains no OSM API
polling or tile requests. Catalog updates require a version and saved-ID migration.
