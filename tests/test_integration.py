#!/usr/bin/env python3
"""Integration regression set, including assertions that must fail rather than silently pass."""
import json
import struct
import zlib
import os
from pathlib import Path
import subprocess
import shutil
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

    def test_plain_host_bootstrap_matches_the_ordered_fragments(self):
        names = ('registry', 'seeded_objects', 'object_state', 'providers', 'script_host',
                 'pack_io', 'calendar', 'archipelago', 'rule_snapshots')
        source = ''.join((ROOT/'lua/poptracker_host'/f'{name}.lua').read_text() for name in names)
        self.assertEqual((ROOT/'lua/host.lua').read_text(),
                         source.replace('__PACKSANITY_EPOCH_SECONDS__', '0'))

    def test_computed_registration_native_callbacks_and_hint_mapping(self):
        result = self.run_pack('--all-variants','--fixture',str(ROOT/'tests/scenarios/example-pack.json'),'--json')
        self.assertEqual(result.returncode,0,result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(len(report['variants']),2)
        for variant in report['variants']:
            self.assertEqual(variant['items'],1)
            self.assertEqual(variant['highlights']['area/chest'],1)
        self.assertIn('PackSanity callback fixture',result.stderr)

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

    def test_apworld_wrong_counts_fail(self):
        fixture = Path(self.temp.name)/'bad-world.json'
        fixture.write_text('{"expect":{"locations":999}}')
        result = self.run_world('--fixture',str(fixture))
        self.assertNotEqual(result.returncode,0)
        self.assertIn('mismatch',result.stderr)

    def test_apworld_invalid_container_fails_before_import(self):
        bad = Path(self.temp.name)/'broken.apworld'
        with zipfile.ZipFile(bad,'w') as archive:
            archive.writestr('broken/__init__.py','raise RuntimeError("IMPORT SHOULD NOT HAPPEN")')
            archive.writestr('broken/archipelago.json',json.dumps({'game':'Broken','version':999,'compatible_version':999}))
        result = self.run_world(world=bad)
        self.assertNotEqual(result.returncode,0)
        self.assertNotIn('IMPORT SHOULD NOT HAPPEN',result.stderr)

    def test_directory_symlinks_are_rejected(self):
        pack = Path(self.temp.name)/'symlink-pack'
        shutil.copytree(ROOT/'tests/fixtures/callback-pack', pack)
        (pack/'outside.lua').symlink_to(ROOT/'lua/host.lua')
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

    def test_required_yaml_reconstructs_when_provided(self):
        world = Path(self.temp.name)/'yaml_world.apworld'
        source = (ROOT/'tests/fixtures/tiny_world/__init__.py').read_text()
        source = source.replace('ut_can_gen_without_yaml = True','ut_can_gen_without_yaml = False')
        with zipfile.ZipFile(world,'w') as archive:
            archive.writestr('yaml_world/__init__.py',source)
        yaml = Path(self.temp.name)/'player.yaml'
        yaml.write_text('name: PackSanity\ngame: PackSanity World\nPackSanity World: {}\n')
        result = self.run_world('--yaml',str(yaml),'--json',world=world)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout)['locations'],1)

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
