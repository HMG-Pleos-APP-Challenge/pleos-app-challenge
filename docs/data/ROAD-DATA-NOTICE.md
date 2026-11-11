# Namyang road and cell database attribution

© OpenStreetMap contributors. Downloaded from Geofabrik:
https://download.geofabrik.de/asia/south-korea-261108.osm.pbf
https://www.openstreetmap.org/copyright

The OSM road extract (`namyang-osm-roads.geojson`) and derived road-cell catalog
(`app/src/main/assets/exploration/namyang.roadcells` and accompanying metadata)
are made available under the Open Database License 1.0:
https://opendatacommons.org/licenses/odbl/1-0/
The license notice and full-text URI are preserved in LICENSE-OSM-ODbL-1.0.txt.

Boundary clipping uses SGIS boundary data modified by vuski/admdongkor,
under KOGL Type 1 and CC BY 4.0; preserve the notices in LICENSE-DATA.txt.

Changes made: selected public motor-road classes, excluded explicit restricted
access / conditional-access ways and tunnels, clipped the roads 25m inside the
Namyang boundary, projected to a fixed local metre grid, and built eligible
100m cells with at least 20m of road inside a 10m inset of each cell.
The extract contains the original geometry of selected intersecting ways;
the runtime catalog is clipped to the Namyang boundary.
The machine-readable road extract, complete runtime catalog and transformation
script are included in this repository. Distribute these data and license notices
along with the app, or make their source download available to app recipients.
The data license does not designate the Android program source as ODbL.

Source distribution date: 2026-11-08.
PBF MD5 verified against publisher: df1366afa39ed9ede72de7b81726dea7
PBF SHA-256: 2539f6a674b9166acee836af1815558ab6264181372bbf1dbadafebb0accaa97
Road extract SHA-256: ae66645ad4527c93e8b0a3a1b2994a3faada1eaa7da4d25ba8fcf156463915a0
Runtime catalog SHA-256 (LF): c1e2771f4f016739388feaba32857df0cd435843a5fef094c7b9a6ed74f94ec9
