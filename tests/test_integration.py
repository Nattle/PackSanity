#!/usr/bin/env python3
"""Integration regression set, including assertions that must fail rather than silently pass."""
import json
import struct
import zlib
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class LoadingIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        built = subprocess.run([str(ROOT/'test-pack.sh'), str(ROOT/'tests/fixtures/minimal'), '--json'],
                               capture_output=True, text=True, timeout=300)
        if built.returncode:
            raise RuntimeError(built.stderr[-4000:])
        if json.loads(built.stdout)['status'] != 'passed':
            raise RuntimeError(built.stdout)
        cls.env = dict(os.environ, JAVA_HOME=(ROOT/'build/java-home.txt').read_text().strip(),
                       PACKSANITY_NATIVE_DIR=str(ROOT/'build/native-meson'))
        cls.cli = ROOT/'cli/build/install/cli/bin/cli'
        cls.temp = tempfile.TemporaryDirectory(prefix='packsanity-integration-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.world = Path(cls.temp.name)/'tiny_world.apworld'
        with zipfile.ZipFile(cls.world,'w') as archive:
            for source in (ROOT/'tests/fixtures/tiny_world').rglob('*.py'):
                archive.write(source, 'tiny_world/'+source.name)
        setup = subprocess.run([os.environ.get('PACKSANITY_PYTHON', 'python3'), str(ROOT/'python/tools/prepare_runtime.py')],
                               capture_output=True,text=True,timeout=1800)
        if setup.returncode:
            raise RuntimeError(setup.stderr[-4000:])
        cls.python = Path(setup.stdout.strip())

    def run_pack(self, *options, pack=None):
        return subprocess.run([str(self.cli), str(pack or ROOT/'tests/fixtures/callback-pack'), *options],
                              env=self.env, text=True,capture_output=True,timeout=60)

    def run_world(self, *options, world=None):
        return subprocess.run([str(self.python), '-I', '-S',
                               str(ROOT/'tools/test_world.py'), str(world or self.world), *options],
                              env=self.env, text=True,capture_output=True,timeout=60)

    def test_computed_registration_native_callbacks_and_hint_mapping(self):
        result = self.run_pack('--all-variants','--fixture',str(ROOT/'tests/scenarios/example-pack.json'),'--json')
        self.assertEqual(result.returncode,0,result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(len(report['variants']),2)
        for variant in report['variants']:
            self.assertEqual(variant['items'],1)
            self.assertEqual(variant['highlights']['area/chest'],1)
        self.assertIn('PackSanity callback fixture',result.stderr)

    def test_computed_registrations_activate_objects_immediately(self):
        pack = Path(self.temp.name) / 'computed'
        (pack / 'data').mkdir(parents=True)
        (pack / 'scripts').mkdir()
        (pack / 'manifest.json').write_text('{"package_uid":"computed"}')
        (pack / 'data/objects.txt').write_text(
            '\ufeff[/* item */ {"type":"static",,"codes":"switch,]",},]'
        )
        (pack / 'data/places.json').write_text('[{"name":"Area","sections":[{"name":"Chest"}]}]')
        (pack / 'data/late.json').write_text('[{"type":"toggle","codes":"late"}]')
        (pack / 'scripts/init.lua').write_text("""
            local root = 'data/'
            assert(Tracker:FindObjectForCode('switch') == nil)
            Tracker:AddItems(root .. 'objects.txt')
            assert(Tracker:ProviderCountForCode('switch') == 1)
            Tracker:AddLocations({root .. 'places.json'})
            assert(Tracker:FindObjectForCode('@Area/Chest'))
            Archipelago:AddItemHandler('late', function()
                Tracker:AddItems(root .. 'late.json')
                assert(Tracker:FindObjectForCode('late')).Active = true
            end)
        """)
        scenario = pack.parent / 'computed-scenario.json'
        scenario.write_text(json.dumps({
            'events': [{'type': 'item', 'index': 0, 'item': 1, 'name': 'Late', 'player': 1}],
            'expect': {'items': 2, 'providerCounts': {'late': 1}},
        }))
        result = self.run_pack('--strict', '--fixture', str(scenario), pack=pack)
        self.assertEqual(result.returncode, 0, result.stderr)
        native_dir = Path(self.env['PACKSANITY_NATIVE_DIR'])
        result = subprocess.run([
            sys.executable, str(ROOT / 'tools/test_pack.py'), str(pack),
            '--host', str(native_dir / 'packsanity-lua'), '--bootstrap', str(native_dir / 'host.lua'),
        ], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_lua_jni_round_trips_standard_utf8_and_embedded_nuls(self):
        source = Path(self.temp.name) / 'LuaUnicodeProbe.java'
        source.write_text(r'''
            import dev.nattle.packsanity.lua.LuaNativeRuntime;

            class LuaUnicodeProbe {
                public static void main(String[] args) {
                    try (var lua = new LuaNativeRuntime(16 * 1024 * 1024, 100_000)) {
                        lua.execute(
                            "function echo(s) assert(s:sub(1,4) == '🧭'); " +
                            "assert(utf8.len(s) == 2); return s end " +
                            "function fail(s) error(s) end", "@test"
                        );
                        String text = "🧭" + (char) 0;
                        if (!text.equals(lua.callString("echo", text))) {
                            throw new AssertionError("Lua changed the Unicode text");
                        }
                        try {
                            lua.callString("fail", "🧭");
                            throw new AssertionError("Lua error was lost");
                        } catch (IllegalStateException error) {
                            if (!error.getMessage().endsWith("🧭")) throw error;
                        }
                    }
                }
            }
        ''', encoding='utf-8')
        java = Path(self.env['JAVA_HOME']) / 'bin/java'
        jars = ROOT / 'cli/build/install/cli/lib'
        result = subprocess.run(
            [str(java), '--class-path', str(jars / '*'), str(source)],
            env=self.env, text=True, capture_output=True, timeout=60,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_live_registration_keeps_duplicate_items_and_stable_late_locations(self):
        pack = Path(self.temp.name) / 'live-definitions'
        for folder in ('items', 'locations', 'scripts'):
            (pack / folder).mkdir(parents=True)
        (pack / 'manifest.json').write_text('{"package_uid":"live-definitions"}')
        (pack / 'items/Étage.json').write_text('[{"type":"static","codes":"shared"}]', encoding='utf-8')
        (pack / 'items/other.json').write_text('[{"type":"toggle","codes":"shared"}]')
        (pack / 'locations/a-late.json').write_text('[{"name":"Area 1","sections":[{"name":"Chest"}]}]')
        (pack / 'locations/b-child.json').write_text(
            '[{"name":"Child","parent":"Area-1","sections":[{"name":"Chest"}]}]'
        )
        (pack / 'locations/z-start.json').write_text('[{"name":"Area-1","sections":[{"name":"Chest"}]}]')
        (pack / 'scripts/init.lua').write_text("""
            local items = 'items/'
            Tracker:AddItems(items .. 'étage.json')
            assert(Tracker:FindObjectForCode('shared').Type == 'static')
            Tracker:AddItems(items .. 'other.json')
            Tracker:AddLocations('locations/z-start.json')
            Tracker:FindObjectForCode('@Area-1/Chest').Highlight = 1
            Archipelago:AddItemHandler('late', function()
                Tracker:AddLocations('locations/b-child.json')
                Tracker:FindObjectForCode('@Area-1/Child/Chest').Highlight = 3
                Tracker:AddLocations('locations/a-late.json')
                Tracker:FindObjectForCode('@Area 1/Chest').Highlight = 2
            end)
        """, encoding='utf-8')
        scenario = pack.parent / 'live-definitions-scenario.json'
        scenario.write_text(json.dumps({
            'events': [{'type': 'item', 'index': 0, 'item': 1, 'name': 'Late', 'player': 1}],
            'expect': {'items': 2, 'sections': 3, 'highlights': {
                'area-1/chest': 1, 'area-1--2/chest': 2, 'child/chest': 3,
            }},
        }))
        zipped = pack.with_suffix('.zip')
        with zipfile.ZipFile(zipped, 'w') as archive:
            for path in pack.rglob('*'):
                if path.is_file():
                    archive.write(path, path.relative_to(pack).as_posix())
        for source in (pack, zipped):
            with self.subTest(source=source):
                result = self.run_pack('--strict', '--fixture', str(scenario), pack=source)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_location_parents_only_use_earlier_successful_registrations(self):
        pack = Path(self.temp.name) / 'location-parents.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"location-parents"}')
            archive.writestr('locations/unused.json', '[{"name":"Unused"}]')
            archive.writestr('models/rooms.data', '[{"name":"Room","metadata":{"flag":true}}]')
            archive.writestr('locations/child.json',
                             '[{"name":"Child","parent":"Unused","sections":[{"name":"Chest"}]}]')
            archive.writestr('locations/late-child.json',
                             '[{"name":"Annex","parent":"Room","sections":[{"name":"Check"}]}]')
            archive.writestr('locations/ref.json',
                             '[{"name":"Mirror","sections":[{"name":"Alias","ref":"Room/Annex/Check"}]}]')
            archive.writestr('layouts/fallback.json', '{}')
            archive.writestr('scripts/init.lua', """
                assert(not Tracker:AddLayouts({'layouts/missing.json', 'layouts/fallback.json'}))
                assert(Tracker:AddLocations('models/rooms.data'))
                assert(Tracker:FindObjectForCode('@Room'))
                assert(Tracker:AddLocations('locations/child.json'))
                local chest = assert(Tracker:FindObjectForCode('@Child/Chest'))
                assert(chest.FullID == 'Child/Chest')
                assert(not Tracker:FindObjectForCode('@Unused/Child/Chest'))
                chest.Highlight = 1
                Archipelago:AddItemHandler('late', function()
                    assert(Tracker:AddLocations({'locations/late-child.json', 'locations/ref.json'}))
                    local check = assert(Tracker:FindObjectForCode('@Room/Annex/Check'))
                    assert(check.FullID == 'Room/Annex/Check')
                    assert(Tracker:FindObjectForCode('@Mirror/Check') == check)
                    check.Highlight = 2
                end)
            """)
        scenario = pack.with_suffix('.json')
        scenario.write_text(json.dumps({
            'events': [{'type': 'item', 'index': 0, 'item': 1, 'name': 'Late', 'player': 1}],
            'expect': {'highlights': {'child/chest': 1, 'annex/check': 2}},
        }))
        result = self.run_pack('--fixture', str(scenario), '--json', pack=pack)
        self.assertEqual(0, result.returncode, result.stderr)
        variant = json.loads(result.stdout)['variants'][0]
        self.assertEqual(["Could not resolve location parent 'Unused' for 'Child'"], variant['diagnostics'])
        self.assertIn('layouts/fallback.json', variant['registeredFiles'])
        self.assertNotIn('layouts/missing.json', variant['registeredFiles'])

    def test_failed_optional_layout_does_not_fail_strict_mode(self):
        pack = Path(self.temp.name) / 'layout-fallback.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"layout-fallback"}')
            archive.writestr('layouts/fallback.json', '{}')
            archive.writestr('layouts/broken.json', '{"bad":')
            archive.writestr('layouts/wrong-shape.json', '[]')
            archive.writestr('scripts/init.lua', """
                assert(not Tracker:AddLayouts('layouts/broken.json'))
                assert(not Tracker:AddLayouts('layouts/wrong-shape.json'))
                if not Tracker:AddLayouts('layouts/optional.json') then
                    assert(Tracker:AddLayouts('layouts/fallback.json'))
                end
            """)
        result = self.run_pack('--strict', pack=pack)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_location_reference_prefers_a_registered_suffix_until_exact_file_loads(self):
        pack = Path(self.temp.name) / 'registered-references.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"registered-references"}')
            archive.writestr(
                'locations/active.json',
                '[{"name":"Outer","children":[{"name":"Room","sections":[{"name":"Chest"}]}]}]',
            )
            archive.writestr(
                'locations/preferred.json',
                '[{"name":"Annex","children":[{"name":"Room","sections":[{"name":"Chest"}]}]}]',
            )
            archive.writestr('locations/exact.json', '[{"name":"Room","sections":[{"name":"Chest"}]}]')
            archive.writestr(
                'locations/view.json', '[{"name":"Mirror","sections":[{"name":"Alias","ref":"Room/Chest"}]}]'
            )
            archive.writestr('scripts/init.lua', """
                Tracker:AddLocations({'locations/view.json', 'locations/preferred.json', 'locations/active.json'})
                local alias = assert(Tracker:FindObjectForCode('@Mirror/Chest'))
                assert(alias == Tracker:FindObjectForCode('@Annex/Room/Chest'))
                assert(alias.FullID == 'Annex/Room/Chest')
                alias.Highlight = 1
                Archipelago:AddItemHandler('load exact location', function()
                    Tracker:AddLocations('locations/exact.json')
                    local exact = assert(Tracker:FindObjectForCode('@Room/Chest'))
                    assert(exact == Tracker:FindObjectForCode('@Mirror/Chest'))
                    assert(exact.FullID == 'Room/Chest')
                    exact.Highlight = 2
                end)
            """)
        first = self.run_pack('--strict', '--json', pack=pack)
        self.assertEqual(first.returncode, 0, first.stderr)
        highlights = json.loads(first.stdout)['variants'][0]['highlights']
        self.assertEqual(1, highlights['annex/room/chest'])
        self.assertEqual(0, highlights['outer/room/chest'])
        self.assertNotIn('room/chest', highlights)
        scenario = pack.with_suffix('.json')
        scenario.write_text(json.dumps({
            'events': [{'type': 'item', 'index': 0, 'item': 1, 'name': 'Late', 'player': 1}],
            'expect': {'highlights': {'annex/room/chest': 1, 'room/chest': 2}},
        }))
        result = self.run_pack('--strict', '--fixture', str(scenario), pack=pack)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_pack_module_search_order_and_load_script_contract(self):
        pack = Path(self.temp.name) / 'module-search.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"module-search"}')
            archive.writestr('scripts/group/module/init.lua', 'return {...}')
            archive.writestr('group/module.lua', 'error("root module shadows scripts init")')
            archive.writestr('fallback/module/init.lua', 'return 42')
            archive.writestr('scripts/prefer.lua', 'return "scripts file"')
            archive.writestr('scripts/prefer/init.lua', 'error("init shadows file")')
            archive.writestr('scripts/effect.lua', 'calls = (calls or 0) + 1; return false')
            archive.writestr('scripts/init.lua', """
                local module = require('group.module')
                assert(module[1] == 'group.module')
                assert(require('group.module') == module)
                assert(require('group.module.init')[1] == 'group.module.init')
                assert(require('scripts/group/module/init')[1] == 'scripts/group/module/init')
                assert(require('fallback.module') == 42)
                assert(require('prefer') == 'scripts file')
                assert(require('effect') == false)
                assert(require('effect') == false)
                assert(calls == 1)
                assert(ScriptHost:LoadScript('/scripts/effect.lua') == true)
                assert(ScriptHost:LoadScript('/effect.lua') == false)
                assert(ScriptHost:LoadScript('/scripts/effect.lua') == true)
                assert(calls == 3)
                assert(Tracker:AddClasses('missing.json') == false)
            """)
        result = self.run_pack('--strict', pack=pack)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_registration_matches_exact_archive_and_explicit_variant_paths(self):
        pack = Path(self.temp.name) / 'archive-registration-paths.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"paths","variants":{"standard":{}}}')
            archive.writestr('items/States.json', '[]')
            archive.writestr('items/states.json', '[{"type":"static","codes":"wrong"}]')
            archive.writestr('items/items.json', '[{"type":"static","codes":"base"}]')
            archive.writestr('standard/items/items.json',
                             '[{"type":"toggle","codes":"variant","initial_active_state":true}]')
            archive.writestr('scripts/init.lua', """
                Tracker:AddItems('items/States.json')
                assert(Tracker:ProviderCountForCode('wrong') == 0)
                Tracker:AddItems('standard/items/items.json')
                assert(Tracker:ProviderCountForCode('variant') == 1)
                local first = assert(Tracker:FindObjectForCode('variant'))
                first.Active = false
                Tracker:AddItems('items/items.json')
                assert(Tracker:ProviderCountForCode('variant') == 1)
                assert(Tracker:FindObjectForCode('variant') == first)
                first.Active = true
                assert(Tracker:ProviderCountForCode('variant') == 2)
                assert(Tracker:ProviderCountForCode('base') == 0)
                Archipelago:AddItemHandler('repeat registration', function()
                    Tracker:AddItems('ITEMS/ITEMS.JSON')
                    assert(Tracker:ProviderCountForCode('variant') == 3)
                end)
            """)
        result = self.run_pack('--strict', '--json', pack=pack)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(2, json.loads(result.stdout)['variants'][0]['items'])
        scenario = pack.with_suffix('.json')
        scenario.write_text(json.dumps({
            'events': [{'type': 'item', 'index': 0, 'item': 1, 'name': 'Late', 'player': 1}],
            'expect': {'items': 3, 'providerCounts': {'variant': 3, 'wrong': 0, 'base': 0}},
        }))
        result = self.run_pack('--strict', '--fixture', str(scenario), pack=pack)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_schema_only_locations_and_generated_item_ids(self):
        pack = Path(self.temp.name) / 'schema-and-ids.zip'
        with zipfile.ZipFile(pack, 'w') as archive:
            archive.writestr('manifest.json', '{"package_uid":"schema-and-ids"}')
            archive.writestr('data/areas.json', '[{"name":"Room","custom_metadata":{"group":"example"}}]')
            archive.writestr('items/a.json', '[{"type":"static","img":"one.png"}]')
            archive.writestr('items/b.json', '[{"type":"static","img":"two.png"}]')
            archive.writestr('data/display.json', '[{"img":"three.png"}]')
            archive.writestr('scripts/init.lua', """
                Tracker:AddLocations('data/areas.json')
                assert(Tracker:FindObjectForCode('@Room'))
                Tracker:AddItems({'items/a.json', 'items/b.json'})
                Tracker:AddItems('data/display.json')
                local icons = {}
                for index = 0, 2 do
                    local item = assert(Tracker:FindObjectForCode('item-' .. index))
                    icons[item.Icon] = true
                end
                assert(icons['one.png'] and icons['two.png'] and icons['three.png'])
            """)
        result = self.run_pack('--strict', '--json', pack=pack)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(3, json.loads(result.stdout)['variants'][0]['items'])

    def test_wrong_hint_target_expectation_fails(self):
        fixture = Path(self.temp.name)/'bad-hint.json'
        fixture.write_text(json.dumps({'expect':{'highlights':{'Area/Wrong chest':1}}}))
        result = self.run_pack('--fixture',str(fixture))
        self.assertNotEqual(result.returncode,0)
        self.assertIn('mismatch',result.stderr)

    def test_unknown_variant_fails(self):
        self.assertNotEqual(self.run_pack('--variant','nonexistent').returncode,0)

    def test_undeclared_variant_does_not_silently_fall_back(self):
        pack = Path(self.temp.name)/'no-variants'
        shutil.copytree(ROOT/'tests/fixtures/callback-pack',pack)
        manifest = json.loads((pack/'manifest.json').read_text())
        manifest.pop('variants')
        (pack/'manifest.json').write_text(json.dumps(manifest))
        result = self.run_pack('--all-variants','--json',pack=pack)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(len(json.loads(result.stdout)['variants']),1)
        result = self.run_pack('--variant','typo',pack=pack)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Unknown variant',result.stderr)

    def test_directory_and_zip_loading_match(self):
        archive = Path(self.temp.name)/'callback.zip'
        with zipfile.ZipFile(archive,'w') as zip:
            for source in (ROOT/'tests/fixtures/callback-pack').rglob('*'):
                if source.is_file(): zip.write(source,'wrapper/'+source.relative_to(ROOT/'tests/fixtures/callback-pack').as_posix())
            zip.writestr('wrapper/schema/manifest.json', '{}')
            zip.writestr('README.txt', 'Release notes')
        direct = json.loads(self.run_pack('--json').stdout)['variants']
        zipped = self.run_pack('--json',pack=archive)
        self.assertEqual(zipped.returncode,0,zipped.stderr)
        self.assertEqual(json.loads(zipped.stdout)['variants'],direct)

    def test_real_apworld_import_reconstruction_and_received_item_replay(self):
        result = self.run_world('--fixture',str(ROOT/'tests/scenarios/example-world.json'),'--json')
        self.assertEqual(result.returncode,0,result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['locations'],1)
        self.assertEqual(report['state']['inventory'][0]['count'],1)
        self.assertTrue(report['state']['locations'][0]['reachable'])

    def test_world_can_exclude_a_scripted_location(self):
        fixture = json.loads((ROOT/'tests/scenarios/example-world.json').read_text())
        fixture['slot_data'] = {'exclude_scripted_location': True}
        path = Path(self.temp.name) / 'scripted-exclusion.json'
        path.write_text(json.dumps(fixture))
        result = self.run_world('--fixture', str(path), '--json')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_world_declared_deferred_entrance_updates_reachability_without_pairings(self):
        fixture = Path(self.temp.name) / 'deferred-entrance.json'
        chest = {'id': 2001, 'name': 'Chest', 'region': 'Hall', 'checked': False, 'excluded': False}
        snapshot = {
            'received_items': [{'item_id': 1001, 'location_id': -1, 'player': 4, 'flags': 1}],
            'missing_locations': [2001],
        }
        fixture.write_text(json.dumps({
            'slot_data': {'deferred_discovery': True},
            'updates': [
                {'state': snapshot, 'expect': {
                    'locations': [dict(chest, reachable=False)],
                    'required_stored_data_keys': ['Door:4:0'],
                    'entrances': [],
                }},
                {'state': dict(snapshot, stored_data={'Door:4:0': [105]}), 'expect': {
                    'locations': [dict(chest, reachable=True)],
                    'entrances': [],
                }},
            ],
        }))
        result = self.run_world('--slot', '4', '--fixture', str(fixture), '--json')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_tracker_replay_attaches_local_item_origin_only(self):
        location = {'id': 2001, 'name': 'Chest', 'region': 'Hall', 'checked': False, 'excluded': False}
        fixture = Path(self.temp.name) / 'item-origin.json'
        fixture.write_text(json.dumps({
            'slot_data': {'require_local_item_origin': True},
            'updates': [
                {
                    'state': {'received_items': [{
                        'item_id': 1001, 'location_id': 2001, 'player': player, 'flags': 1,
                    }]},
                    'expect': {'locations': [dict(location, reachable=reachable)]},
                }
                for player, reachable in ((5, False), (4, True))
            ],
        }))
        result = self.run_world('--slot', '4', '--fixture', str(fixture), '--json')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_apworld_wrong_counts_fail(self):
        fixture = Path(self.temp.name)/'bad-world.json'
        fixture.write_text('{"expect":{"locations":999}}')
        result = self.run_world('--fixture',str(fixture))
        self.assertNotEqual(result.returncode,0)
        self.assertIn('mismatch',result.stderr)

    def test_apworld_invalid_container_fails_before_import(self):
        bad = Path(self.temp.name)/'broken.apworld'
        source = Path(self.temp.name)/'broken'
        source.mkdir()
        (source/'__init__.py').write_text('raise RuntimeError("IMPORT SHOULD NOT HAPPEN")')
        (source/'archipelago.json').write_text(json.dumps({'game':'Broken','version':999,'compatible_version':999}))
        with zipfile.ZipFile(bad,'w') as archive:
            for entry in source.iterdir():
                archive.write(entry, f'broken/{entry.name}')
        for world in (bad, source):
            with self.subTest(world=world):
                result = self.run_world(world=world)
                self.assertNotEqual(result.returncode,0)
                self.assertIn('UNSUPPORTED_APWORLD_CONTAINER',result.stderr)
                self.assertNotIn('IMPORT SHOULD NOT HAPPEN',result.stderr)

    def test_python_and_kotlin_prefer_the_package_world_manifest(self):
        world = Path(self.temp.name) / 'tiny_world_with_manifest.apworld'
        source = (ROOT / 'tests/fixtures/tiny_world/__init__.py').read_text()
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('archipelago.json', '{"game":"Ignored","compatible_version":999}')
            archive.writestr(f'{world.stem}/__init__.py', source)
            archive.writestr(f'{world.stem}/archipelago.json', json.dumps({
                'game': 'PackSanity World', 'world_version': '1.2.3',
                'compatible_version': 7, 'minimum_ap_version': '0.6.8',
            }))
        fixture = ROOT / 'tests/scenarios/example-world.json'
        result = self.run_world('--fixture', str(fixture), '--json', world=world)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report['state']['locations'][0]['reachable'])

    def test_directory_symlinks_are_rejected(self):
        pack = Path(self.temp.name)/'symlink-pack'
        shutil.copytree(ROOT/'tests/fixtures/callback-pack', pack)
        (pack/'outside.lua').symlink_to(ROOT/'lua/poptracker_host/registry.lua')
        result = self.run_pack(pack=pack)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('symbolic link', result.stderr)

    def test_pack_scenario_without_player_identity_does_not_activate_hint(self):
        fixture = json.loads((ROOT/'tests/scenarios/example-pack.json').read_text())
        fixture['events'] = [e for e in fixture['events'] if e['type'] != 'connected']
        path = Path(self.temp.name)/'no-identity.json'
        path.write_text(json.dumps(fixture))
        result = self.run_pack('--fixture', str(path))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('mismatch', result.stderr)

    def test_missing_yaml_fails_reconstruction(self):
        world = Path(self.temp.name)/'yaml_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        source = source.replace('ut_can_gen_without_yaml = True', 'ut_can_gen_without_yaml = False')
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('yaml_world/__init__.py', source)
        result = self.run_world(world=world)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('YAML', result.stderr)

    def test_missing_dependency_fails_before_world_executes(self):
        world = Path(self.temp.name)/'dependency_world.apworld'
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('dependency_world/__init__.py', 'raise RuntimeError("MUST NOT IMPORT")')
            archive.writestr('dependency_world/requirements.txt', 'unbundled_dependency>=1')
        result = self.run_world(world=world)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('not bundled', result.stderr)
        self.assertNotIn('MUST NOT IMPORT', result.stderr)

    def test_world_prints_go_to_stderr_without_corrupting_json(self):
        world = Path(self.temp.name)/'printing_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('printing_world/__init__.py', 'print("WORLD IMPORT LOG")\n'+source)
        result = self.run_world('--json', world=world)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['status'], 'passed')
        self.assertIn('WORLD IMPORT LOG', result.stderr)

    def test_apworld_imports_staged_registration_and_json_fallback(self):
        world = Path(self.temp.name)/'registration_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('registration_world/requirements.txt', 'schema==0.7.8.0\n')
            archive.writestr('registration_world/__init__.py',
                             'from worlds.AutoSNIClient import SNIClient, Read, SnesReader, SnesData\n'
                             'from orjson import orjson\n'
                             'assert orjson.loads(orjson.dumps({"sample": 1})) == {"sample": 1}\n'+source)
        result = self.run_world('--fixture', str(ROOT/'tests/scenarios/example-world.json'), '--json', world=world)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['state']['inventory'][0]['count'], 1)

    def test_world_generation_error_without_a_message_reports_its_type(self):
        world = Path(self.temp.name)/'error_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        source = source.replace('    def create_regions(self):',
                                '    def generate_early(self):\n        raise AttributeError\n\n'
                                '    def create_regions(self):')
        with zipfile.ZipFile(world, 'w') as archive:
            archive.writestr('error_world/__init__.py', source)
        result = self.run_world('--json', world=world)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stderr.splitlines()[-1], 'FAIL AttributeError')

    def make_mapped_world(self, external=False, wrapped=False, uppercase=False):
        name = 'mapped_world'
        archive_root = name.title() if uppercase else name
        world = Path(self.temp.name)/(archive_root+'.apworld')
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        config = {'map_page_folder':'tracker', 'map_page_maps':['maps.json'],
                  'map_page_locations':['locations.json']}
        if external:
            config['external_pack_key'] = 'companion'
        source = source.replace('    ut_can_gen_without_yaml = True',
                                '    ut_can_gen_without_yaml = True\n    tracker_world = '+repr(config))
        maps = json.dumps({'maps':[{'name':'Hall', 'img':'hall.png'}]})
        locations = json.dumps([{'name':'Hall', 'sections':[{'name':'Chest'}],
                                'map_locations':[{'map':'Hall','x':1,'y':2}]}])
        def chunk(kind, data):
            return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
        png = (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>2I5B',1,1,8,6,0,0,0))+
               chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00\xff'))+chunk(b'IEND',b''))
        prefix = 'wrapper/' if wrapped else ''
        with zipfile.ZipFile(world,'w') as archive:
            archive.writestr(prefix+archive_root+'/__init__.py',source)
            if not external:
                archive.writestr(prefix+archive_root+'/tracker/maps.json',maps)
                archive.writestr(prefix+archive_root+'/tracker/locations.json',locations)
                archive.writestr(prefix+archive_root+'/tracker/hall.png',png)
        companion = Path(self.temp.name)/'companion.zip'
        if external:
            with zipfile.ZipFile(companion,'w') as archive:
                archive.writestr('maps.json',maps)
                archive.writestr('locations.json',locations)
                archive.writestr('hall.png',png)
        return world, companion

    def test_companion_map_paths_are_validated_in_the_companion(self):
        world, companion = self.make_mapped_world(external=True)
        result = self.run_world('--external-pack',str(companion),'--json',world=world)
        self.assertEqual(result.returncode,0,result.stderr)
        map = json.loads(result.stdout)['state']['map']['maps'][0]
        self.assertEqual(map['image_path'],'external-pack/hall.png')
        self.assertEqual(map['locations'][0]['location_id'],2001)

    def test_missing_companion_image_fails(self):
        world, companion = self.make_mapped_world(external=True)
        with zipfile.ZipFile(companion) as archive:
            contents = {name:archive.read(name) for name in archive.namelist() if name != 'hall.png'}
        with zipfile.ZipFile(companion,'w') as archive:
            for name, data in contents.items(): archive.writestr(name,data)
        result = self.run_world('--external-pack',str(companion),world=world)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Map image not found in companion',result.stderr)

    def test_unusable_explicit_companion_fails_verification(self):
        world, companion = self.make_mapped_world(external=True)
        with zipfile.ZipFile(companion, 'w') as archive:
            archive.writestr('README.txt', 'No maps in this archive')
        result = self.run_world('--external-pack', str(companion), world=world)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('could not use companion map pack', result.stderr)

    def test_wrapped_apworld_is_rejected_by_the_package_gate(self):
        world, _ = self.make_mapped_world(wrapped=True)
        result = self.run_world('--json',world=world)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('INVALID_APWORLD',result.stderr)

    def test_uppercase_apworld_maps_use_the_normalized_archive(self):
        world, _ = self.make_mapped_world(uppercase=True)
        result = self.run_world('--json',world=world)
        self.assertEqual(result.returncode,0,result.stderr)
        map = json.loads(result.stdout)['state']['map']['maps'][0]
        self.assertEqual(map['image_path'],'mapped_world/tracker/hall.png')
        self.assertEqual(map['locations'][0]['location_id'],2001)

    def test_apworld_directory_reconstructs_maps_and_replays_state(self):
        world, _ = self.make_mapped_world(uppercase=True)
        parent = Path(self.temp.name) / 'world source'
        with zipfile.ZipFile(world) as archive:
            archive.extractall(parent)
        source = parent / world.stem
        fixture = ROOT / 'tests/scenarios/example-world.json'
        result = self.run_world('--fixture', str(fixture), '--json', world=source)
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report['world'], str(source.resolve()))
        self.assertEqual(report['updates'], 1)
        world_map = report['state']['map']['maps'][0]
        self.assertEqual(world_map['image_path'], 'mapped_world/tracker/hall.png')
        self.assertEqual(world_map['locations'][0]['location_id'], 2001)

    def test_required_yaml_reconstructs_when_provided(self):
        world = Path(self.temp.name)/'yaml_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        source = source.replace('ut_can_gen_without_yaml = True','ut_can_gen_without_yaml = False')
        with zipfile.ZipFile(world,'w') as archive:
            archive.writestr('yaml_world/__init__.py',source)
        yaml = Path(self.temp.name)/'player.yaml'
        player_options = ('name: PackSanity\ngame: PackSanity World\nPackSanity World:\n'
                          '  exclude_locations: [Early checks]\n')
        yaml.write_text(player_options)
        result = self.run_world('--yaml',str(yaml),'--json',world=world)
        self.assertEqual(result.returncode,0,result.stderr)
        locations = json.loads(result.stdout)['state']['locations']
        self.assertEqual(len(locations), 1)
        self.assertTrue(locations[0]['excluded'])

        yaml.write_text(player_options.replace('Early checks', 'Unknown check'))
        result = self.run_world('--yaml', str(yaml), world=world)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Unknown excluded locations: Unknown check', result.stderr)

        yaml.write_text(player_options + '  exclude_locations: []\n')
        result = self.run_world('--yaml', str(yaml), world=world)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Duplicate key exclude_locations', result.stderr)

        yaml.write_text(
            'name: PackSanity\ngame: PackSanity World\n'
            'defaults: &defaults {exclude_locations: [Early checks]}\n'
            'PackSanity World:\n  <<: *defaults\n'
        )
        result = self.run_world('--yaml', str(yaml), '--json', world=world)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)['state']['locations'][0]['excluded'])

    def test_interpreter_enforces_single_loaded_world(self):
        code = '''
import sys
sys.path.insert(0, sys.argv[1])
from packsanity_ut_adapter import MobileUniversalTracker
tracker=MobileUniversalTracker()
tracker.load_world(sys.argv[2], 'tiny_world')
tracker.load_world(sys.argv[2], 'tiny_world')
try:
    tracker.load_world(sys.argv[3], 'other_world')
except Exception as error:
    assert 'new interpreter' in str(error).lower(), str(error)
else:
    raise RuntimeError('world switch must fail')
'''
        other = Path(self.temp.name)/'other_world.apworld'
        with zipfile.ZipFile(other,'w') as archive: archive.writestr('other_world/__init__.py','')
        result = subprocess.run([str(self.python),'-I','-S','-c',code,
                                 str(ROOT/'build/python/payload'),str(self.world),str(other)],capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr)


if __name__ == '__main__':
    unittest.main()
