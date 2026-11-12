import copy
import math
import unittest
from shapely.geometry import box
from analyze_conquest_segments import analyze, candidate_catalog


def fixture(split=False, oneway=None):
    xy = {1: (0, 0), 2: (100, 0), 3: (200, 0),
          4: (0, 100), 5: (0, -100), 6: (200, 100), 7: (200, -100)}
    xscale = math.pi / 180 * 6371008.8 * math.cos(math.radians(37.2))
    yscale = math.pi / 180 * 6371008.8
    nodes = [{"id": n, "lon": 126.8+x/xscale, "lat": 37.2+y/yscale} for n, (x, y) in xy.items()]
    tags = {"highway": "residential", "name": "test"}
    if oneway:
        tags["oneway"] = oneway
    paths = [[1, 2], [2, 3]] if split else [[1, 2, 3]]
    ways = [{"id": 10+i, "version": 1, "tags": dict(tags), "nodes": path}
            for i, path in enumerate(paths)]
    ways += [{"id": 20+i, "version": 1, "tags": {"highway": "service"}, "nodes": path}
             for i, path in enumerate([[1, 4], [1, 5], [3, 6], [3, 7]])]
    return {"nodes": nodes, "ways": ways}


class SegmentAnalysisTest(unittest.TestCase):
    region = box(-1000, -1000, 1000, 1000)

    def test_degree_two_way_boundary_is_not_an_intersection(self):
        rows = analyze(fixture(split=True), self.region)
        self.assertEqual(1, len(rows))
        self.assertEqual([1, 2, 3], rows[0]["nodes"])
        self.assertEqual([], rows[0]["reviewReasons"])
        self.assertEqual(2, len(rows[0]["directions"]))
        self.assertTrue(rows[0]["directions"][0]["id"].startswith("osm:chain:"))

    def test_single_way_ids_and_oneway_direction(self):
        for tag, endpoints in [("yes", (1, 3)), ("-1", (3, 1))]:
            row = analyze(fixture(oneway=tag), self.region)[0]
            self.assertEqual(1, len(row["directions"]))
            direction = row["directions"][0]
            self.assertEqual(endpoints, (direction["fromNode"], direction["toNode"]))
            self.assertEqual(f"osm:way:10:v1:{endpoints[0]}:{endpoints[1]}", direction["id"])

    def test_inconsistent_oneway_chain_is_held(self):
        data = fixture(split=True, oneway="yes")
        data["ways"][1]["tags"]["oneway"] = "-1"
        row = analyze(data, self.region)[0]
        self.assertIn("inconsistent_direction", row["reviewReasons"])
        self.assertEqual([], row["directions"])

    def test_coordinate_crossing_without_shared_node_does_not_split(self):
        data = fixture()
        duplicate = dict(data["nodes"][1], id=8)
        data["nodes"].append(duplicate)
        data["ways"].append({"id": 99, "version": 1,
                             "tags": {"highway": "service", "bridge": "yes"}, "nodes": [4, 8, 7]})
        row = analyze(data, self.region)[0]
        self.assertEqual([1, 2, 3], row["nodes"])
        self.assertEqual([], row["reviewReasons"])

    def test_real_shared_node_splits_and_short_segments_are_flagged(self):
        data = fixture()
        data["ways"].append({"id": 99, "version": 1, "tags": {"highway": "service"}, "nodes": [2, 4]})
        # Move the junction to 1/10 of its old progress: first piece becomes ~10m.
        data["nodes"][1]["lon"] = 126.8+(data["nodes"][1]["lon"]-126.8)/10
        rows = analyze(data, self.region)
        self.assertEqual([[1, 2], [2, 3]], [r["nodes"] for r in rows])
        self.assertIn("shorter_than_50m", rows[0]["reviewReasons"])

    def test_boundary_and_dead_end_are_not_invented_junctions(self):
        data = fixture()
        data["ways"] = data["ways"][:1]
        row = analyze(data, box(-10, -10, 150, 10))[0]
        self.assertIn("boundary_crossing", row["reviewReasons"])
        self.assertIn("non_junction_endpoint", row["reviewReasons"])
        self.assertEqual([1, 2, 3], row["nodes"])

    def test_duplicate_geometry_is_held_and_missing_reference_fails(self):
        data = fixture()
        data["ways"].append(dict(copy.deepcopy(data["ways"][0]), id=99))
        self.assertTrue(all("overlapping_source_edge" in r["reviewReasons"] for r in analyze(data, self.region)))
        data["nodes"] = data["nodes"][1:]
        with self.assertRaisesRegex(ValueError, "Missing node"):
            analyze(data, self.region)

    def test_order_independence_and_closed_loop_hold(self):
        data = fixture(split=True)
        expected = analyze(data, self.region)
        data["ways"].reverse()
        data["nodes"].reverse()
        self.assertEqual(expected, analyze(data, self.region))
        data = fixture()
        data["ways"] = [dict(data["ways"][0], nodes=[1, 2, 4, 1])]
        self.assertIn("closed_loop", analyze(data, self.region)[0]["reviewReasons"])

    def test_catalog_filters_held_rows_and_preserves_direction_coordinates(self):
        rows = analyze(fixture(oneway="-1"), self.region)
        lines = candidate_catalog(rows).splitlines()
        self.assertEqual(3, len(lines))
        fields = lines[2].split("|")
        self.assertEqual(["3", "1"], fields[2:4])
        self.assertEqual(rows[0]["directions"][0]["points"],
                         [list(map(float, p.split(","))) for p in fields[4].split(";")])
        rows[0]["reviewReasons"] = ["boundary_crossing"]
        with self.assertRaisesRegex(ValueError, "No candidate"):
            candidate_catalog(rows)


if __name__ == "__main__":
    unittest.main()
