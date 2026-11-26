from __future__ import annotations

from pathlib import Path
import builtins
import sys
import tempfile
import types
import unittest
from unittest import mock
import zipfile

from runtime_test_support import staged_payload


sys.path.insert(0, str(staged_payload()))

from packsanity_ut_adapter import (  # noqa: E402
    MobileUniversalTracker,
    TrackerAdapterError,
    TrackerSession,
)
from packsanity_ut_entrances import _entrance_storage_key_by_source
from packsanity_ut_maps import _normalize_player_markers
from packsanity_ut_packages import _local_rule_builder_fallback, _normalized_package_path


class PositionContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tracker = MobileUniversalTracker()
        self.tracker._loaded_package_root = "example_world"

    def test_normalizes_single_legacy_marker_and_pack_relative_icon(self) -> None:
        self.assertEqual(
            [
                {
                    "x": 12.5,
                    "y": 42.0,
                    "map_id": "overworld",
                    "icon_path": "example_world/tracker/icons/player.png",
                    "size": 24.0,
                }
            ],
            _normalize_player_markers(
                "example_world",
                "tracker",
                "overworld",
                24.0,
                (12.5, 42, "icons/player.png"),
            ),
        )

    def test_supports_multiple_markers_and_filters_hidden_or_invalid_results(self) -> None:
        markers = _normalize_player_markers(
            "example_world",
            "tracker",
            "dungeon",
            18.0,
            [
                (1, 2, "icons/one.png"),
                (3, 4),
                (-1, 8, "icons/hidden.png"),
                (float("nan"), 9, "icons/invalid.png"),
                None,
            ],
        )

        self.assertEqual(2, len(markers))
        self.assertEqual("example_world/tracker/icons/one.png", markers[0]["icon_path"])
        self.assertIsNone(markers[1]["icon_path"])

    def test_supports_tuple_of_markers(self) -> None:
        markers = _normalize_player_markers(
            "example_world",
            "tracker",
            "dungeon",
            25.0,
            ((10, 20), (30, 40)),
        )

        self.assertEqual([10.0, 30.0], [marker["x"] for marker in markers])

    def test_rejects_marker_icon_paths_that_escape_the_pack(self) -> None:
        for external in (False, True):
            with self.subTest(external=external), self.assertRaises(TrackerAdapterError):
                _normalize_player_markers(
                    "example_world", "tracker", "overworld", 24.0, (1, 2, "../../outside.png"), external=external,
                )

    def test_position_storage_does_not_invalidate_cached_logic(self) -> None:
        world = types.SimpleNamespace(found_entrances_datastorage_key=("{player}_{team}_entrances",))
        session = TrackerSession("Example", 4, "Player", 2, {}, object())
        session.stored_data = {
            "4_2_coords": {"x": 1},
            "4_2_entrances": ["A"],
        }
        first = self.tracker._logic_signature(world, session, (), set(), set())
        session.stored_data["4_2_coords"] = {"x": 99}
        second = self.tracker._logic_signature(world, session, (), set(), set())
        session.stored_data["4_2_entrances"] = ["A", "B"]
        third = self.tracker._logic_signature(world, session, (), set(), set())

        self.assertEqual(first, second)
        self.assertNotEqual(second, third)

    def test_unrelated_companion_pack_does_not_disable_world_tracking(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory) / "companion.zip"
            with zipfile.ZipFile(external, "w") as archive:
                archive.writestr("manifest.json", "{}")
            self.tracker._loaded_package_path = external
            world = types.SimpleNamespace(
                tracker_world={
                    "external_pack_key": "pack_path",
                    "map_page_maps": ["maps/missing.json"],
                }
            )
            session = TrackerSession("Example", 4, "Player", 0, {}, object())
            session.external_pack_path = external

            self.assertIsNone(self.tracker._map_state(world, session))
            self.assertIsNone(session.external_pack_path)

    def test_hybrid_map_uses_world_definitions_and_companion_image(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apworld = Path(directory) / "example_world.apworld"
            companion = Path(directory) / "companion.zip"
            with zipfile.ZipFile(apworld, "w") as archive:
                archive.writestr("example_world/tracker/maps.json", '[{"name":"world","img":"world.png"}]')
                archive.writestr("example_world/tracker/locations.json", '[{"name":"Town",'
                                 '"sections":[{"name":"Chest"}],'
                                 '"map_locations":[{"map":"world","x":12,"y":20}]}]')
            with zipfile.ZipFile(companion, "w") as archive:
                archive.writestr("world.png", b"image")
                archive.writestr("maps.json", '[{"name":"other","img":"other.png"}]')
            self.tracker._loaded_package_path = apworld
            world = types.SimpleNamespace(
                location_name_to_id={"Chest": 42},
                tracker_world={
                    "external_pack_key": "pack_path",
                    "map_page_folder": "tracker",
                    "map_page_maps": "maps.json",
                    "map_page_locations": "locations.json",
                },
            )
            session = TrackerSession("Example", 4, "Player", 0, {}, object())
            session.external_pack_path = companion

            state = self.tracker._map_state(world, session)

            self.assertEqual("world", state["active_map_id"])
            self.assertEqual("external-pack/world.png", state["maps"][0]["image_path"])
            self.assertEqual([{"location_id": 42, "x": 12.0, "y": 20.0}], state["maps"][0]["locations"])

    def test_invalid_cached_companion_does_not_abort_initialization(self) -> None:
        class FakeWorld:
            ut_can_gen_without_yaml = True

        with tempfile.TemporaryDirectory() as directory:
            invalid_pack = Path(directory) / "cached-companion.zip"
            invalid_pack.write_bytes(b"incomplete download")
            multiworld = types.SimpleNamespace(worlds={1: FakeWorld()}, precollected_items={1: []})
            expected = {"locations": [{"id": 1}], "entrances": [{"name": "North"}]}
            with (
                mock.patch(
                    "packsanity_ut_adapter.AutoWorld.AutoWorldRegister",
                    types.SimpleNamespace(world_types={"Example": FakeWorld}),
                    create=True,
                ),
                mock.patch.object(self.tracker, "_build_multiworld", return_value=multiworld),
                mock.patch.object(self.tracker, "_project_update", return_value=expected),
            ):
                result = self.tracker.initialize(
                    game="Example",
                    slot=1,
                    slot_name="Player",
                    slot_data={},
                    external_pack_path=str(invalid_pack),
                )

            self.assertEqual(expected, result)
            self.assertIsNotNone(self.tracker._session)
            self.assertIsNone(self.tracker._session.external_pack_path)


class PrecollectedInventoryContractTest(unittest.TestCase):
    def test_only_codeless_events_survive_reconstruction_before_initial_replay(self) -> None:
        class FakeWorld:
            ut_can_gen_without_yaml = True

        event = types.SimpleNamespace(code=None)
        other_event = types.SimpleNamespace(code=None)
        multiworld = types.SimpleNamespace(
            worlds={1: FakeWorld()},
            precollected_items={1: [types.SimpleNamespace(code=17), event,
                                   types.SimpleNamespace(code=0)],
                                2: [other_event, types.SimpleNamespace(code=99)]},
        )
        tracker = MobileUniversalTracker()

        def first_replay(*args):
            self.assertEqual({1: [event], 2: [other_event]}, multiworld.precollected_items)
            return {"locations": []}

        with (
            mock.patch("packsanity_ut_adapter.AutoWorld.AutoWorldRegister",
                       types.SimpleNamespace(world_types={"Example": FakeWorld}), create=True),
            mock.patch.object(tracker, "_build_multiworld", return_value=multiworld),
            mock.patch.object(tracker, "_project_update", side_effect=first_replay),
        ):
            tracker.initialize("Example", 1, "Player", {})


class EntranceContractTest(unittest.TestCase):
    def test_changed_server_pairing_does_not_reuse_an_observed_exit_to_the_old_destination(self) -> None:
        old_exit = types.SimpleNamespace(connected_region=None)
        world = types.SimpleNamespace(
            found_entrances_datastorage_key=("Slot:{player}:A",),
            reconnect_found_entrances=lambda key, value: setattr(
                old_exit, "connected_region", types.SimpleNamespace(name="Region label") if value else None
            ),
        )
        session = TrackerSession(
            "Example", 4, "Player", 0, {"entrance_pairings": {"A": "B"}},
            types.SimpleNamespace(
                worlds={1: world}, get_regions=lambda player: [], get_entrances=lambda player: [old_exit]
            ),
        )
        tracker = MobileUniversalTracker()
        tracker._apply_stored_data(session, {"Slot:4:A": True})
        state = types.SimpleNamespace(can_reach=lambda exit: True)
        self.assertTrue(MobileUniversalTracker._entrance_payload(world, session, state)[0]["reachable"])

        session.slot_data = {"entrance_pairings": {"A": "B"}, "other": 1}
        self.assertTrue(MobileUniversalTracker._entrance_payload(world, session, state)[0]["reachable"])

        session.slot_data = {"entrance_pairings": {"A": "C"}}
        changed = MobileUniversalTracker._entrance_payload(world, session, state)[0]
        self.assertEqual("C", changed["destination_region"])
        self.assertIsNone(changed["reachable"])

        tracker._apply_stored_data(session, {"Slot:4:A": False})
        tracker._apply_stored_data(session, {"Slot:4:A": True})
        self.assertTrue(MobileUniversalTracker._entrance_payload(world, session, state)[0]["reachable"])

    def test_seed_pairings_hide_discovery_and_only_evaluate_exact_exits(self) -> None:
        exact = types.SimpleNamespace(connected_region=types.SimpleNamespace(name="Crypt"))
        ambiguous = types.SimpleNamespace(connected_region=types.SimpleNamespace(name="Tower"))
        regions = [
            types.SimpleNamespace(name="Grove", exits=[exact]),
            types.SimpleNamespace(name="Hall", exits=[ambiguous, ambiguous]),
        ]
        multiworld = types.SimpleNamespace(get_regions=lambda player: regions)
        world = types.SimpleNamespace(
            entrance_pairings={"Grove": "Crypt", "Hall": "Tower"},
            found_entrances_datastorage_key=("{player}_{team}_found",),
        )
        session = TrackerSession(
            "Example", 4, "Player", 2,
            {"entrance_pairings": {"Grove": "Crypt", "Hall": "Tower"}}, multiworld,
        )
        session.stored_data = {"4_2_found": ["Grove"]}
        state = types.SimpleNamespace(can_reach=lambda exit: exit is exact)

        result = MobileUniversalTracker._entrance_payload(world, session, state)

        self.assertEqual(True, result[0]["discovered"])
        self.assertEqual(True, result[0]["reachable"])
        self.assertEqual(False, result[1]["discovered"])
        self.assertIsNone(result[1]["reachable"])

    def test_no_discovery_contract_reports_unknown(self) -> None:
        world = types.SimpleNamespace(entrance_pairings={"A": "B"})
        multiworld = types.SimpleNamespace(get_regions=lambda player: [])
        session = TrackerSession(
            "Example", 1, "Player", None, {"entrance_pairings": {"A": "B"}}, multiworld
        )

        result = MobileUniversalTracker._entrance_payload(world, session, object())

        self.assertIsNone(result[0]["discovered"])
        self.assertIsNone(result[0]["reachable"])

    def test_ignores_pairings_from_fake_generation_without_server_evidence(self) -> None:
        world = types.SimpleNamespace(entrance_pairings={"A": "B"})
        multiworld = types.SimpleNamespace(get_regions=lambda player: [])
        session = TrackerSession("Example", 1, "Player", None, {}, multiworld)

        self.assertEqual([], MobileUniversalTracker._entrance_payload(world, session, object()))

    def test_server_pairing_is_visible_without_reconstructed_pairing(self) -> None:
        world = types.SimpleNamespace(entrance_pairings={"A": "Wrong"})
        matching_exit = types.SimpleNamespace(connected_region=types.SimpleNamespace(name="B"))
        multiworld = types.SimpleNamespace(
            get_regions=lambda player: [types.SimpleNamespace(name="A", exits=[matching_exit])]
        )
        session = TrackerSession(
            "Example", 1, "Player", None, {"entrance_pairings": {"A": "B"}}, multiworld
        )
        state = types.SimpleNamespace(can_reach=lambda exit: True)

        self.assertEqual(
            [{"id": "A", "source_region": "A", "destination_region": "B",
              "discovered": None, "reachable": None, "direction": None}],
            MobileUniversalTracker._entrance_payload(world, session, state),
        )

    def test_server_pairing_is_visible_without_world_pairing_table(self) -> None:
        world = types.SimpleNamespace()
        multiworld = types.SimpleNamespace(get_regions=lambda player: [])
        session = TrackerSession(
            "Example", 1, "Player", None, {"entrance_pairings": {"A": "B"}}, multiworld
        )

        self.assertEqual(
            "B",
            MobileUniversalTracker._entrance_payload(world, session, object())[0]["destination_region"],
        )

    def test_discovery_is_unknown_until_storage_is_retrieved(self) -> None:
        world = types.SimpleNamespace(
            entrance_pairings={"A": "B"},
            found_entrances_datastorage_key=("{player}_{team}_found",),
        )
        multiworld = types.SimpleNamespace(get_regions=lambda player: [])
        session = TrackerSession(
            "Example", 1, "Player", 0, {"entrance_pairings": {"A": "B"}}, multiworld
        )

        self.assertIsNone(MobileUniversalTracker._entrance_payload(world, session, object())[0]["discovered"])
        session.stored_data = {"1_0_found": []}
        self.assertEqual(False, MobileUniversalTracker._entrance_payload(world, session, object())[0]["discovered"])

    def test_per_exit_boolean_storage_and_coupled_reverse_are_server_driven(self) -> None:
        world = types.SimpleNamespace(
            found_entrances_datastorage_key=("Slot:{player}:A", "Slot:{player}:B"),
        )
        multiworld = types.SimpleNamespace(get_regions=lambda player: [])
        session = TrackerSession(
            "Example", 4, "Player", 0,
            {"Entrance Rando": {"A": "B"}, "decoupled": 0}, multiworld,
        )

        before = MobileUniversalTracker._entrance_payload(world, session, object())
        self.assertEqual(2, len(before))
        self.assertEqual({"coupled"}, {row["direction"] for row in before})
        self.assertTrue(all(row["discovered"] is None for row in before))
        session.stored_data = {"Slot:4:A": True, "Slot:4:B": False}
        after = MobileUniversalTracker._entrance_payload(world, session, object())
        self.assertEqual({"A": "B", "B": "A"}, {row["id"]: row["destination_region"] for row in after})
        self.assertTrue(all(row["discovered"] is True for row in after))

    def test_decoupled_storage_does_not_invent_reverse_links(self) -> None:
        world = types.SimpleNamespace(found_entrances_datastorage_key=("Slot:{player}:A",))
        session = TrackerSession(
            "Example", 4, "Player", 0,
            {"Entrance Rando": {"A": "B"}, "decoupled": 1},
            types.SimpleNamespace(get_regions=lambda player: []),
        )
        session.stored_data = {"Slot:4:A": False}
        result = MobileUniversalTracker._entrance_payload(world, session, object())
        self.assertEqual(["A"], [row["id"] for row in result])
        self.assertEqual(False, result[0]["discovered"])
        self.assertEqual("one_way", result[0]["direction"])

    def test_ambiguous_slot_data_tables_are_not_treated_as_pairings(self) -> None:
        world = types.SimpleNamespace(found_entrances_datastorage_key=("Slot:{player}:A",))
        session = TrackerSession(
            "Example", 4, "Player", 0,
            {"first": {"A": "B"}, "second": {"A": "C"}},
            types.SimpleNamespace(get_regions=lambda player: []),
        )
        self.assertEqual([], MobileUniversalTracker._entrance_payload(world, session, object()))

    def test_declared_key_order_resolves_an_exceptional_portal_spelling(self) -> None:
        pairings = {"A": "a", "B": "b", "C": "c", "D": "d", "Shop 1": "shop"}
        keys = tuple(
            "Slot:{player}:" + value
            for source, destination in pairings.items()
            for value in (source.replace("Shop 1", "Shop Previous 1"), destination)
        )
        world = types.SimpleNamespace(found_entrances_datastorage_key=keys)
        session = TrackerSession("Example", 4, "Player", 0, {"links": pairings}, object())
        by_source = _entrance_storage_key_by_source(world, session, pairings)
        self.assertEqual("Slot:4:Shop Previous 1", by_source["Shop 1"])
        self.assertEqual("Slot:4:shop", by_source["shop"])


class LoadedWorldLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.apworld = Path(self.temporary_directory.name) / "example_world.apworld"
        with zipfile.ZipFile(self.apworld, "w") as archive:
            archive.writestr("example_world/__init__.py", "")
        self.tracker = MobileUniversalTracker()
        self.tracker._loaded_package_root = "example_world"
        self.tracker._loaded_package_path = self.apworld.resolve()
        self.tracker._loaded_package_metadata = {
            "package_root": "example_world",
            "games": ["Example Game"],
            "requires_yaml_by_game": {"Example Game": False},
            "requirements": [],
        }

    def test_same_apworld_can_be_reused_after_tracker_session_shutdown(self) -> None:
        self.tracker.close()

        metadata = self.tracker.load_world(str(self.apworld), "example_world")

        self.assertEqual(["Example Game"], metadata["games"])

    def test_same_root_from_a_different_archive_still_requires_a_fresh_interpreter(self) -> None:
        replacement = Path(self.temporary_directory.name) / "replacement.apworld"
        with zipfile.ZipFile(replacement, "w") as archive:
            archive.writestr("example_world/__init__.py", "")

        with self.assertRaisesRegex(TrackerAdapterError, "different APWorld archive"):
            self.tracker.load_world(str(replacement), "example_world")

    def test_bundled_legacy_rule_builder_fallback_is_scoped_to_import(self) -> None:
        with zipfile.ZipFile(self.apworld, "a") as archive:
            archive.writestr("example_world/rule_builder.py", "")
        core_module = types.ModuleType("rule_builder")
        previous_module = sys.modules.get("rule_builder")
        sys.modules["rule_builder"] = core_module
        original_import = builtins.__import__
        try:
            with _local_rule_builder_fallback(self.apworld, "example_world"):
                with self.assertRaises(ModuleNotFoundError):
                    __import__("rule_builder", fromlist=("RuleWorldMixin",))
            self.assertIs(builtins.__import__, original_import)
        finally:
            if previous_module is None:
                sys.modules.pop("rule_builder", None)
            else:
                sys.modules["rule_builder"] = previous_module

    def test_normalizes_legacy_uppercase_package_casing_without_changing_source(self) -> None:
        legacy = Path(self.temporary_directory.name) / "Example_World.apworld"
        with zipfile.ZipFile(legacy, "w") as archive:
            archive.writestr("Example_World/__init__.py", "")

        normalized = _normalized_package_path(legacy, "example_world")

        self.assertNotEqual(legacy, normalized)
        self.assertEqual("example_world", normalized.stem.rsplit("-", 1)[0])
        with zipfile.ZipFile(normalized) as archive:
            self.assertIn("example_world/__init__.py", archive.namelist())
        with zipfile.ZipFile(legacy) as archive:
            self.assertIn("Example_World/__init__.py", archive.namelist())


if __name__ == "__main__":
    unittest.main()
