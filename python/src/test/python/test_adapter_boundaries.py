"""Characterization of reconstruction, package gates, and live-result caching."""

from __future__ import annotations

from argparse import Namespace
import copy
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest import mock
import zipfile

from test_packsanity_ut_adapter import MobileUniversalTracker, TrackerAdapterError, TrackerSession
import packsanity_ut_adapter as adapter
from packsanity_ut_requirements import BUNDLED_PYTHON_REQUIREMENTS, _read_requirements, _requirement_is_available
import packsanity_ut_upstream as upstream


class PackageGateTest(unittest.TestCase):
    def test_pinned_option_rolling_is_available_to_world_imports(self):
        from Generate import roll_settings
        from Options import Toggle
        from worlds import AutoWorldRegister

        world = types.SimpleNamespace(options_dataclass=types.SimpleNamespace(type_hints={"extra": Toggle}))
        with mock.patch.dict(AutoWorldRegister.world_types, {"Example": world}):
            options = roll_settings({"game": "Example", "Example": {"extra": 1}})

        self.assertEqual("Example", options.game)
        self.assertEqual(1, options.extra.value)

    def test_dependency_gate_matches_shipped_runtime_versions(self):
        runtime_root = Path(__file__).resolve().parents[3]
        locked = json.loads((runtime_root / "runtime.lock.json").read_text())["purePythonDependencies"]
        self.assertEqual({name.lower(): version for name, version in locked.items()}, BUNDLED_PYTHON_REQUIREMENTS)
        self.assertTrue(_requirement_is_available("pathspec>=1.0"))
        self.assertFalse(_requirement_is_available("typing_extensions>=4.16"))

    def test_declared_requirements_are_sorted_deduplicated_and_not_installed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "example.apworld"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("example/requirements.txt", "# comment\n schema>=0.7\nPyYAML==6.0.3\n")
                archive.writestr("example/nested/requirements.txt", "schema>=0.7\n-r other.txt\n")
            self.assertEqual(
                ["-r other.txt", "PyYAML==6.0.3", "schema>=0.7"],
                _read_requirements(path),
            )

    def test_fixed_dependency_gate_rejects_external_and_unknown_requirements(self):
        for requirement in ("schema>=0.7,<1", "schema==0.7.8.0", "PyYAML==6.0.*", "typing_extensions>=4"):
            with self.subTest(requirement=requirement):
                self.assertTrue(_requirement_is_available(requirement))
        for requirement in (
            "schema>=1", "schema==0.7.8rc1", 'schema; python_version >= "3.0"',
            "unbundled", "schema @ https://example.org/a.whl", "-r a.txt",
        ):
            with self.subTest(requirement=requirement):
                self.assertFalse(_requirement_is_available(requirement))

    def test_unavailable_requirements_block_world_import(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "example.apworld"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("example/__init__.py", "raise AssertionError('must not execute')")
                archive.writestr("example/requirements.txt", "missing_dependency>=1")
            with self.assertRaisesRegex(TrackerAdapterError, "not bundled.*missing_dependency"):
                MobileUniversalTracker().load_world(str(path), "example")

    def test_manifest_gates_run_before_import(self):
        for fields, message in (
            ({"compatible_version": 999}, "too new"),
            ({"compatible_version": 7, "minimum_ap_version": "0.7.0"}, "requires Archipelago 0.7.0"),
            ({"maximum_ap_version": "0.6.7"}, "supports Archipelago 0.6.7"),
        ):
            with self.subTest(fields=fields), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "example.apworld"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("example/__init__.py", "raise AssertionError('must not import')")
                    archive.writestr("example/archipelago.json", json.dumps({"game": "Example", **fields}))
                with self.assertRaisesRegex(TrackerAdapterError, message):
                    MobileUniversalTracker().load_world(str(path), "example")

    def test_world_import_receives_manifest_and_world_version(self):
        registry = types.SimpleNamespace(world_types={})
        source = """from worlds import AutoWorld
assert __name__ == __package__ == __spec__.name == "worlds.example"
class ExampleWorld:
    ut_can_gen_without_yaml = True
AutoWorld.AutoWorldRegister.world_types["Example"] = ExampleWorld
"""
        manifest = {"game": "Example", "world_version": "1.2.3", "custom_field": "kept"}
        for manifest_path in ("example/archipelago.json", "archipelago.json"):
            with self.subTest(manifest_path=manifest_path), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "example.apworld"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("example/__init__.py", source)
                    archive.writestr(manifest_path, json.dumps(manifest))
                with mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", registry, create=True):
                    try:
                        metadata = MobileUniversalTracker().load_world(str(path), "example")
                        world = registry.world_types["Example"]
                        self.assertEqual(manifest, world.manifest)
                        self.assertEqual((1, 2, 3), world.world_version)
                        self.assertFalse(metadata["requires_yaml_by_game"]["Example"])
                    finally:
                        import sys
                        sys.modules.pop("worlds.example", None)

    def test_tracker_package_verification_reads_metadata_without_executing_desktop_code(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tracker.apworld"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("tracker/archipelago.json", json.dumps({"game": "Universal Tracker", "version": 7}))
                archive.writestr("tracker/TrackerCore.py", "raise AssertionError('desktop code')")
                archive.writestr("tracker/__init__.py", "UT_VERSION = 'v0.3.3'\nraise AssertionError('desktop code')")
            self.assertEqual(
                {"game": "Universal Tracker", "container_version": 7, "version": "0.3.3"},
                MobileUniversalTracker.verify_tracker_package(str(path)),
            )


class ReconstructionContractTest(unittest.TestCase):
    def test_static_slot_data_interpretation_precedes_fake_generation(self):
        calls = []

        class World:
            ut_can_gen_without_yaml = True

            @staticmethod
            def interpret_slot_data(value):
                calls.append(("interpret", value))
                return {"reconstructed": value["seed"]}

        tracker = MobileUniversalTracker()
        multiworld = types.SimpleNamespace(worlds={1: World()}, precollected_items={1: []})

        def build(game, slot_name, options, regen):
            calls.append(("build", game, slot_name, options, regen))
            return multiworld

        with (
            mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", types.SimpleNamespace(world_types={"Example": World}), create=True),
            mock.patch.object(tracker, "_build_multiworld", side_effect=build),
            mock.patch.object(tracker, "_project_update", return_value={"ok": True}),
        ):
            self.assertEqual({"ok": True}, tracker.initialize("Example", 4, "Player", {"seed": 9}))
        self.assertEqual([
            ("interpret", {"seed": 9}),
            ("build", "Example", "Player", {}, {"reconstructed": 9}),
        ], calls)
        self.assertEqual({"seed": 9}, tracker._session.slot_data)

    def test_class_slot_hook_can_mutate_its_input_without_changing_server_snapshot(self):
        class World:
            ut_can_gen_without_yaml = True

            @classmethod
            def interpret_slot_data(cls, value):
                return {"reconstructed": value.pop("seed")}

        tracker = MobileUniversalTracker()
        multiworld = types.SimpleNamespace(worlds={1: World()}, precollected_items={1: []})
        slot_data = {"seed": 9}
        with (
            mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", types.SimpleNamespace(world_types={"Example": World}), create=True),
            mock.patch.object(tracker, "_build_multiworld", return_value=multiworld) as build,
        ):
            session = tracker._reconstruct_session("Example", 4, "Player", slot_data, None, None)
        build.assert_called_once_with("Example", "Player", {}, {"reconstructed": 9})
        self.assertEqual({"seed": 9}, slot_data)
        self.assertEqual(slot_data, session.slot_data)

    def test_instance_slot_hook_rebuilds_after_initial_generation(self):
        class World:
            ut_can_gen_without_yaml = True

            def interpret_slot_data(self, value):
                return {"from_instance": value}

        tracker = MobileUniversalTracker()
        first = types.SimpleNamespace(worlds={1: World()}, precollected_items={1: []})
        second = types.SimpleNamespace(worlds={1: World()}, precollected_items={1: []})

        def generate(game, slot_name, options, regen):
            # Instance hooks are too late to rescue a first generation that needs server data.
            if regen == {"seed": 9}:
                return first
            if regen == {"from_instance": {"seed": 9}}:
                return second
            raise KeyError("seed")

        with (
            mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", types.SimpleNamespace(world_types={"Example": World}), create=True),
            mock.patch.object(tracker, "_build_multiworld", side_effect=generate) as build,
            mock.patch.object(tracker, "_project_update", return_value={}),
        ):
            tracker.initialize("Example", 4, "Player", {"seed": 9})
        self.assertEqual([{"seed": 9}, {"from_instance": {"seed": 9}}], [call.args[3] for call in build.call_args_list])
        self.assertIs(second, tracker._session.multiworld)

    def test_yaml_less_world_without_slot_hook_receives_server_data(self):
        class World:
            ut_can_gen_without_yaml = True

        tracker = MobileUniversalTracker()
        multiworld = types.SimpleNamespace(worlds={1: World()}, precollected_items={1: []})
        slot_data = {"cooksanity": 1, "shinesanity": 0}
        with (
            mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", types.SimpleNamespace(world_types={"Example": World}), create=True),
            mock.patch.object(tracker, "_build_multiworld", return_value=multiworld) as build,
            mock.patch.object(tracker, "_project_update", return_value={}),
        ):
            tracker.initialize("Example", 4, "Player", slot_data)
        build.assert_called_once_with("Example", "Player", {}, slot_data)

    def test_fake_generation_runs_only_the_ordered_tracker_stages(self):
        calls = []
        excluded = types.SimpleNamespace(value={"Skip", "Group"})
        excluded.verify = mock.Mock(side_effect=lambda *args: setattr(excluded, "value", {"Skip", "One", "Two"}))
        world = types.SimpleNamespace(
            options=types.SimpleNamespace(exclude_locations=excluded),
            location_name_groups={"Restored group": {"Three"}},
        )

        class Multiworld:
            worlds = {1: world}
            plando_options = 0
            player_ids = (1,)

            def __init__(self, players):
                self.players = players

            def set_seed(self, *values):
                calls.append(("seed", values))

            def set_options(self, options):
                calls.append(("options", options))

        stages = ("generate_early", "create_regions", "create_items", "set_rules",
                  "connect_entrances", "generate_basic")
        base_world = type("BaseWorld", (), {step: object() for step in stages})

        def generate(multiworld, step):
            calls.append((step, set(excluded.value) if step == "generate_early" else multiworld))
            if step == "set_rules":
                excluded.value.update({"Scripted location", "Restored group"})

        with (
            mock.patch.object(upstream, "MultiWorld", Multiworld),
            mock.patch.object(upstream, "CollectionState", return_value="collection state") as collection_state,
            mock.patch.object(adapter.AutoWorld, "World", base_world),
            mock.patch.object(adapter.AutoWorld, "AutoWorldRegister", types.SimpleNamespace(world_types={"Example": object}), create=True),
            mock.patch.object(adapter.AutoWorld, "call_all", side_effect=generate),
            mock.patch.object(upstream, "exclusion_rules", side_effect=lambda mw, player, excluded: calls.append(("exclude", player, excluded))),
            mock.patch.object(MobileUniversalTracker, "_options_namespace", return_value=Namespace()),
        ):
            result = MobileUniversalTracker()._build_multiworld("Example", "Player", {}, {"seed": 9})
        self.assertEqual([
            "seed", "options", "generate_early", "create_regions", "create_items", "set_rules",
            "exclude", "connect_entrances", "generate_basic",
        ], [call[0] for call in calls])
        self.assertIn(("generate_early", {"Skip", "One", "Two"}), calls)
        self.assertIn(("exclude", 1, {"Skip", "One", "Two", "Three", "Scripted location"}), calls)
        excluded.verify.assert_called_once_with(world, "Player", 0)
        self.assertEqual({"Example": {"seed": 9}}, result.re_gen_passthrough)
        self.assertEqual("default", result.enforce_deferred_connections)
        collection_state.assert_called_once_with(result, True)
        self.assertTrue(result.generation_is_fake)
        self.assertEqual({1: "Player"}, result.player_name)

    def test_yaml_merges_flat_and_game_options_and_rejects_generation_constructs(self):
        tracker = MobileUniversalTracker()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "player.yaml"
            path.write_text("game: Example\nname: Player\ndifficulty: normal\nExample: {difficulty: hard}\n")
            result = tracker._load_yaml_options(str(path), "Example", "Player")
            self.assertEqual("hard", result["difficulty"])
            path.write_text("trigger: []\nlinked_options: []\n")
            with self.assertRaisesRegex(TrackerAdapterError, "linked_options, trigger"):
                tracker._load_yaml_options(str(path), "Example", "Player")

    def test_yaml_merge_precedence_and_inline_duplicate_checks(self):
        tracker = MobileUniversalTracker()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "player.yaml"
            path.write_text(
                "defaults: &defaults {difficulty: easy, mode: casual}\n"
                "Example:\n"
                "  <<: [{<<: *defaults, difficulty: normal}, {difficulty: hard, mode: expert}]\n"
                "  mode: standard\n"
            )
            result = tracker._load_yaml_options(str(path), "Example", "Player")
            self.assertEqual("normal", result["difficulty"])
            self.assertEqual("standard", result["mode"])

            path.write_text("Example: {<<: {difficulty: easy, difficulty: hard}}\n")
            with self.assertRaisesRegex(TrackerAdapterError, "Duplicate key difficulty"):
                tracker._load_yaml_options(str(path), "Example", "Player")


class LiveResultContractTest(unittest.TestCase):
    def setUp(self):
        self.tracker = MobileUniversalTracker()
        self.world = types.SimpleNamespace(
            item_name_to_id={"Zeta": 8, "Alpha": 3}, item_id_to_name={3: "Alpha"}, location_id_to_name={},
        )
        region = types.SimpleNamespace(name="Room", exits=[])
        self.location = types.SimpleNamespace(address=9, name="Check", parent_region=region, progress_type="normal")
        self.item = types.SimpleNamespace(name="Alpha", code=3, classification=0, advancement=True)
        self.multiworld = types.SimpleNamespace(
            game={1: "Example"},
            worlds={1: self.world},
            create_item=lambda name, player: self.item,
            get_locations=lambda player: [self.location],
            get_reachable_locations=lambda state, player: [self.location],
            get_regions=lambda player: [region],
        )
        self.session = TrackerSession("Example", 4, "Player", 2, {}, self.multiworld)
        self.tracker._session = self.session
        self.collection_state = types.SimpleNamespace(
            collect=mock.Mock(), sweep_for_advancements=mock.Mock(), reachable_regions={1: []},
        )

    def test_storage_only_update_reuses_logic_but_recomputes_maps(self):
        maps = [{"active_map_id": "first"}, {"active_map_id": "second"}]
        with (
            mock.patch.object(upstream, "CollectionState", return_value=self.collection_state) as collection,
            mock.patch.object(self.tracker, "_map_state", side_effect=maps) as map_state,
        ):
            first = self.tracker.update([{"item_id": 3, "flags": 2}], set(), {9})
            second = self.tracker.update([{"item_id": 3, "flags": 2}], set(), {9}, {"position": 7})
        self.assertEqual(1, collection.call_count)
        self.assertEqual(2, map_state.call_count)
        self.assertEqual("first", first["map"]["active_map_id"])
        self.assertEqual("second", second["map"]["active_map_id"])
        self.assertEqual(first["locations"], second["locations"])
        self.assertEqual([
            {"id": 3, "name": "Alpha", "count": 1}, {"id": 8, "name": "Zeta", "count": 0},
        ], first["inventory"])
        self.assertEqual(2, self.item.classification)

    def test_checked_changes_recompute_logic(self):
        with mock.patch.object(upstream, "CollectionState", return_value=self.collection_state) as collection:
            self.tracker.update([], set(), {9})
            checked = self.tracker.update([], {9}, set())
        self.assertEqual(2, collection.call_count)
        self.assertTrue(checked["locations"][0]["checked"])

    def test_unknown_positive_item_is_reported_and_negative_items_are_ignored(self):
        with mock.patch.object(upstream, "CollectionState", return_value=self.collection_state):
            ignored = self.tracker.update([{"item_id": -1}], set(), set())
            self.assertTrue(all(item["count"] == 0 for item in ignored["inventory"]))
            with self.assertRaisesRegex(TrackerAdapterError, "does not define item id 100"):
                self.tracker.update([{"item_id": 100}], set(), set())

    def test_known_membership_includes_unmatched_ids_and_respects_an_empty_list(self):
        with mock.patch.object(upstream, "CollectionState", return_value=self.collection_state):
            reconstructed = self.tracker.update([], set(), None)
            empty_slot = self.tracker.update([], set(), set())
            complete = self.tracker.update([], {12}, set())
            partial = self.tracker.update([], {9}, {12})
        self.assertEqual([9], [row["id"] for row in reconstructed["locations"]])
        self.assertEqual([], empty_slot["locations"])
        self.assertEqual([(12, True, None)], [
            (row["id"], row["checked"], row["reachable"]) for row in complete["locations"]
        ])
        self.assertEqual({9: (True, True), 12: (False, None)}, {
            row["id"]: (row["checked"], row["reachable"]) for row in partial["locations"]
        })

    def test_late_slot_data_rebuilds_then_replays_progress_inventory_and_storage(self):
        self.session.stored_data = {"position": 7}
        self.session.yaml_options = {"difficulty": "hard"}
        self.session.external_pack_path = Path("/fixture/maps.zip")
        updated_graph = copy.copy(self.multiworld)
        updated_graph.get_reachable_locations = lambda state, player: []
        replacement = TrackerSession("Example", 4, "Player", 2, {"option": 1}, updated_graph)
        with (
            mock.patch.object(upstream, "CollectionState", return_value=self.collection_state),
            mock.patch.object(self.tracker, "_reconstruct_session", return_value=replacement) as rebuild,
        ):
            initial = self.tracker.update([], set(), {9})
            updated = self.tracker.update([{"item_id": 3}], {9}, set(), {"new": 1}, {"option": 1})
            self.tracker.update([{"item_id": 3}], {9}, set(), slot_data={"option": 1})
        self.assertTrue(initial["locations"][0]["reachable"])
        self.assertFalse(updated["locations"][0]["reachable"])
        self.assertTrue(updated["locations"][0]["checked"])
        self.assertEqual(1, updated["inventory"][0]["count"])
        rebuild.assert_called_once_with("Example", 4, "Player", {"option": 1}, 2, {"difficulty": "hard"})
        self.assertIs(replacement, self.tracker._session)
        self.assertEqual(self.session.external_pack_path, replacement.external_pack_path)
        self.assertEqual({"position": 7, "new": 1}, replacement.stored_data)

    def test_failed_rebuild_publishes_no_result_and_can_retry(self):
        updated = TrackerSession("Example", 4, "Player", 2, {"option": 1}, self.multiworld)
        with (
            mock.patch.object(upstream, "CollectionState", return_value=self.collection_state),
            mock.patch.object(self.tracker, "_reconstruct_session", side_effect=[ValueError("bad data"), updated]),
        ):
            with self.assertRaisesRegex(TrackerAdapterError, "Could not reconstruct from updated slot data: bad data"):
                self.tracker.update([], set(), {9}, slot_data={"option": 1})
            self.assertIs(self.session, self.tracker._session)
            self.assertEqual({}, self.session.slot_data)
            self.tracker.update([], set(), {9}, slot_data={"option": 1})
        self.assertIs(updated, self.tracker._session)

    def test_failed_entrance_hook_rebuilds_and_retries_same_storage(self):
        old_exit = types.SimpleNamespace(connected_region=None)
        new_exit = types.SimpleNamespace(connected_region=None)
        calls = []

        def reconnect(exit, fail):
            def apply(key, value):
                calls.append((key, value))
                exit.connected_region = self.location.parent_region
                if fail:
                    raise RuntimeError("entrance update failed after connecting")
            return apply

        self.world.found_entrances_datastorage_key = "Door:{player}"
        self.world.reconnect_found_entrances = reconnect(old_exit, True)
        self.multiworld.get_entrances = lambda player: [old_exit]
        self.multiworld.get_reachable_locations = lambda state, player: []
        new_world = copy.copy(self.world)
        new_world.reconnect_found_entrances = reconnect(new_exit, False)
        rebuilt = copy.copy(self.multiworld)
        rebuilt.worlds = {1: new_world}
        rebuilt.get_entrances = lambda player: [new_exit]
        rebuilt.get_reachable_locations = lambda state, player: [self.location] if new_exit.connected_region else []
        replacement = TrackerSession("Example", 4, "Player", 2, {}, rebuilt)
        with (
            mock.patch.object(upstream, "CollectionState", return_value=self.collection_state),
            mock.patch.object(self.tracker, "_reconstruct_session", return_value=replacement) as rebuild,
        ):
            self.assertFalse(self.tracker.update([], set(), {9})["locations"][0]["reachable"])
            with self.assertRaisesRegex(RuntimeError, "entrance update failed after connecting"):
                self.tracker.update([], set(), {9}, {"Door:4": True})
            self.assertEqual({}, self.session.stored_data)
            result = self.tracker.update([], set(), {9}, {"Door:4": True})
        rebuild.assert_called_once()
        self.assertEqual([("Door:4", True), ("Door:4", True)], calls)
        self.assertTrue(result["locations"][0]["reachable"])

    def test_invalid_late_slot_data_does_not_mutate_session(self):
        self.session.slot_data = {"metadata": 1}
        self.session.logic_signature = ("cached",)
        with self.assertRaisesRegex(TrackerAdapterError, "must be an object"):
            self.tracker.update([], set(), set(), slot_data=[])
        self.assertEqual({"metadata": 1}, self.session.slot_data)
        self.assertEqual(("cached",), self.session.logic_signature)

    def test_json_bridge_normalizes_complete_snapshot_and_keeps_entry_points(self):
        tracker = mock.Mock()
        tracker.update.return_value = {"locations": []}
        with mock.patch.object(adapter, "_TRACKER", tracker):
            self.assertEqual({"locations": []}, json.loads(adapter.update_state(json.dumps({
                "received_items": [{"item_id": 3}], "checked_locations": [9, 9],
                "missing_locations": [10], "stored_data": {"key": True}, "slot_data": {"seed": 2},
            }))))
            adapter.shutdown_tracker()
        tracker.update.assert_called_once_with(
            received_items=[{"item_id": 3}], checked_locations={9}, missing_locations={10},
            stored_data={"key": True}, slot_data={"seed": 2},
        )
        tracker.close.assert_called_once_with()
