"""Exercise the UT map projection against a small Archipelago graph and map archive."""
from pathlib import Path
import json
import sys
import tempfile
from types import SimpleNamespace
import unittest
import zipfile

from runtime_test_support import staged_payload

sys.path.insert(0, str(staged_payload()))
from BaseClasses import CollectionState, Location, MultiWorld, Region
from packsanity_ut_adapter import MobileUniversalTracker
from packsanity_ut_maps import _update_graph_markers
from packsanity_ut_models import TrackerSession


class MapGraphMarkersTest(unittest.TestCase):
    def test_projects_graph_sections_with_independent_state_and_map_visibility(self):
        with tempfile.TemporaryDirectory() as folder:
            pack = Path(folder) / "example.apworld"
            self.write_map(pack)
            tracker = MobileUniversalTracker()
            tracker._loaded_package_root = "example"
            tracker._loaded_package_path = pack
            world = SimpleNamespace(
                location_name_to_id={"Chest": 9},
                tracker_world={
                    "map_page_folder": "tracker",
                    "map_page_maps": "maps.json",
                    "map_page_locations": "locations.json",
                    "poptracker_entrance_mapping": {"North Door": "North to Hall"},
                },
            )
            graph = MultiWorld(1)
            graph.worlds[1] = world
            world.origin_region_name = "Menu"
            world.get_region = lambda name: graph.get_region(name, 1)
            world.explicit_indirect_conditions = True
            world.reached_region = lambda state, region: None
            home = Region("Menu", 1, graph)
            hall = Region("Hall", 1, graph)
            graph.regions.extend((home, hall))
            door = home.create_exit("North to Hall")
            pot = Location(1, "Pot", None, hall)
            pot.access_rule = lambda state: False
            hall.locations.append(pot)
            session = TrackerSession("Example", 1, "Player", None, {}, graph)

            def project():
                snapshot = tracker._map_state(world, session)
                state = CollectionState(graph, allow_partial_entrances=True)
                _update_graph_markers(world, session, state, snapshot)
                return snapshot["maps"][0]

            initial = project()
            self.assertEqual([("North Door", "available"), ("Pot", "blocked")], self.states(initial))
            self.assertEqual([{"location_id": 9, "x": 5.5, "y": 7.0, "size": 8.0}], initial["locations"])
            entrance, event = initial["graph_markers"]
            self.assertEqual(("entrance", 12.0, 5.0), (entrance["kind"], entrance["x"], entrance["y"]))
            self.assertEqual(("event", 17.0, 23.0, 6.0), (event["kind"], event["x"], event["y"], event["size"]))
            self.assertEqual(12, initial["location_size"])
            self.assertEqual(2, initial["location_border_thickness"])

            door.connect(hall)
            self.assertEqual([("North Door", "complete"), ("Pot", "available")], self.states(project()))
            pot.access_rule = lambda state: True
            self.assertEqual([("North Door", "complete"), ("Pot", "complete")], self.states(project()))
            world.ut_map_page_hidden_events = {"area": ["Pot"]}
            self.assertEqual([("North Door", "complete")], self.states(project()))
            self.assertEqual([("North Door", "available"), ("Pot", "blocked")], self.states(initial))

    @staticmethod
    def states(asset):
        return [(entry["name"], entry["status"])
                for pin in asset["graph_markers"] for entry in pin["entries"]]

    @staticmethod
    def write_map(pack):
        def location(name, x, y, **geometry):
            return {
                "sections": [{"name": name}],
                "map_locations": [{"map": "area", "x": x, "y": y, **geometry}],
            }

        with zipfile.ZipFile(pack, "w") as archive:
            archive.writestr("example/tracker/maps.json", json.dumps([{
                "name": "area", "img": "area.png", "location_size": 12, "location_border_thickness": 2,
            }]))
            archive.writestr("example/tracker/locations.json", json.dumps([{"children": [
                location("Chest", 5.5, 7, size=8),
                location("North Door", 12, 5),
                location("Pot", 17, 23, size=6),
                location("Unmatched", 3, 4),
            ]}]))
