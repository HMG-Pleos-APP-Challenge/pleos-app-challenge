"""Run with python -m unittest discover -s tools -p test_audit_conquest_roads.py."""
import unittest
import json
from pathlib import Path
import tempfile
from shapely.geometry import box
from audit_conquest_roads import extract, review


class ReviewPolicyTest(unittest.TestCase):
    def classify(self, **tags):
        return review({"highway": "residential", **tags})

    def test_generic_access_can_be_overridden_by_motorcar_permission(self):
        self.assertEqual("candidate:topology_review_required",
                         self.classify(access="no", motorcar="yes"))
        self.assertEqual("exclude:restricted_access",
                         self.classify(access="yes", motor_vehicle="private"))

    def test_conditional_or_mode_direction_is_not_assumed_bidirectional(self):
        self.assertEqual("hold:conditional_or_mode_direction",
                         self.classify(**{"oneway:conditional": "yes @ (08:00-10:00)"}))
        self.assertEqual("hold:conditional_or_mode_direction",
                         self.classify(**{"oneway:motor_vehicle": "no"}))
        self.assertEqual("hold:unknown_direction", self.classify(oneway="reversible"))

    def test_separate_road_policies_are_not_silently_approved(self):
        for tags, reason in [({"junction": "roundabout"}, "roundabout"),
                             ({"highway": "motorway_link"}, "high_speed_road"),
                             ({"tunnel": "yes"}, "tunnel"),
                             ({"highway": "service"}, "additional_road_class")]:
            with self.subTest(tags=tags):
                self.assertEqual("hold:" + reason, self.classify(**tags))

    def test_non_roads_and_unknown_permissions_are_not_candidates(self):
        self.assertEqual("exclude:outside_motor_classes", self.classify(highway="footway"))
        self.assertEqual("exclude:area", self.classify(area="yes"))
        self.assertEqual("hold:unknown_access", self.classify(motor_vehicle="unknown"))


class ExtractionTest(unittest.TestCase):
    def test_preserves_node_ids_directions_and_exact_original_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.osm"
            source.write_text('''<osm version="0.6">
<node id="1" lat="37.2" lon="126.8"/>
<node id="2" lat="37.2001" lon="126.8001"/>
<node id="3" lat="38" lon="127"/>
<node id="4" lat="38.001" lon="127.001"/>
<way id="10" version="2"><nd ref="1"/><nd ref="2"/>
<tag k="highway" v="residential"/><tag k="oneway" v="-1"/></way>
<way id="11" version="1"><nd ref="3"/><nd ref="4"/>
<tag k="highway" v="residential"/></way></osm>''', encoding="utf-8")
            roads = root / "roads.json"
            fixture = {"features": [{"properties": {"osm_id": "10", "osm_version": 2},
                       "geometry": {"coordinates": [[126.8, 37.2], [126.8001, 37.2001]]}}]}
            roads.write_text(json.dumps(fixture), encoding="utf-8")
            ways, nodes, comparison = extract(source, box(-100, -100, 100, 100), roads)
            self.assertEqual([10], [w["id"] for w in ways])
            self.assertEqual([1, 2], ways[0]["nodes"])
            self.assertEqual("-1", ways[0]["tags"]["oneway"])
            self.assertEqual(2, len(nodes))
            self.assertTrue(comparison[0]["versionMatches"])
            self.assertTrue(comparison[0]["geometryMatches"])
            fixture["features"][0]["properties"]["osm_version"] = 3
            fixture["features"][0]["geometry"]["coordinates"].reverse()
            roads.write_text(json.dumps(fixture), encoding="utf-8")
            _, _, comparison = extract(source, box(-100, -100, 100, 100), roads)
            self.assertFalse(comparison[0]["versionMatches"])
            self.assertFalse(comparison[0]["geometryMatches"])


if __name__ == "__main__":
    unittest.main()
