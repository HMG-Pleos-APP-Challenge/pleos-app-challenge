# Namyang landmark database attribution

© OpenStreetMap contributors. Source: Geofabrik `south-korea-261108.osm.pbf`.
https://download.geofabrik.de/asia/south-korea.html
https://www.openstreetmap.org/copyright

The included point extract `namyang-osm-landmarks.json` and derived catalog
`app/src/main/assets/exploration/namyang.landmarks` are made available under
Open Database License 1.0: https://opendatacommons.org/licenses/odbl/1-0/.
Preserve LICENSE-OSM-ODbL-1.0.txt and the SGIS boundary attribution in LICENSE-DATA.txt.
The machine-readable extract, catalog and transformation tool are provided together.

Selected original feature: OSM node 13851756178, version 1, name 성모마리아,
historic=memorial, memorial=statue, statue=virgin, latitude 37.2046411,
longitude 126.8159948. The application display label is 성모마리아 기념상.
Source feature URL: https://www.openstreetmap.org/node/13851756178
This is a dated public-map landmark, not a certified tourist-site inventory or parking verification.

Changes: selected one named landmark in the Namyang SGIS boundary, added a display label,
and attached prototype rule parameters (100m radius / 120s dwell / 15s maximum observation gap).
The rule parameters are app proposals rather than OSM facts.

Source PBF SHA-256: 2539f6a674b9166acee836af1815558ab6264181372bbf1dbadafebb0accaa97
Point extract SHA-256 (LF): 10df2d108213c62f66a7b86a2ad6c1f043883a28bd551275d05ac099e0433715
Runtime catalog SHA-256 (LF): b80fcb47c27a3f4c189d5174c43970b07f311be2155efa03d63516fcd381c524

Rebuild with the Python standard library:
python tools/build_namyang_landmarks.py --source docs/data/namyang-osm-landmarks.json --output <path>

Distribute this data and license notice with the app, or make its source download
available to app recipients. This data license does not designate the Android source program as ODbL.
User collection files and raw personal location data are not part of the public dataset.
