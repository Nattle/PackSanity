"""Exercise the production staging CLI and import its output in an isolated process."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from runtime_test_support import staged_payload


class RuntimeStagingTest(unittest.TestCase):
    def test_staged_payload_imports_adapter_without_the_source_tree_or_bytecode(self):
        root = Path(__file__).resolve().parents[3]
        inputs = staged_payload().parent / "inputs"
        lock = json.loads((root / "runtime.lock.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            output = fixture / "output"
            tracker = inputs / f"UniversalTracker-{lock['universalTracker']['version']}.apworld"
            stage_command = [
                sys.executable, "-B", str(root / "tools/stage_runtime.py"),
                "--archipelago-source", str(inputs / f"Archipelago-{lock['archipelago']['version']}"),
                "--cpython-source", str(inputs / f"Python-{lock['cpython']['version']}"),
                "--site-packages", str(inputs / "site-packages"),
                "--output", str(output),
                "--tracker-archive",
            ]
            subprocess.run([*stage_command, str(tracker)], check=True, capture_output=True, text=True)
            invalid = fixture / "invalid-tracker.apworld"
            invalid.write_bytes(b"corrupt download")
            result = subprocess.run([*stage_command, str(invalid)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Universal Tracker release checksum", result.stderr)
            self.assertEqual(
                lock["runtimeId"],
                (output / "packsanity-runtime-id.txt").read_text(),
            )
            source_modules = {path.name for path in (root / "src/main/python").glob("*.py")}
            self.assertTrue(source_modules.issubset({path.name for path in output.glob("*.py")}))
            self.assertEqual(
                (root / "licenses/UniversalTracker-MIT.txt").read_bytes(),
                (output / "notices/UniversalTracker-MIT.txt").read_bytes(),
            )
            self.assertTrue((output / "notices/packaging/LICENSE").is_file())
            self.assertEqual([], list(output.rglob("*.pyc")))
            resources = fixture / "resource_fixture.zip"
            with zipfile.ZipFile(resources, "w") as archive:
                archive.writestr("resource_fixture/__init__.py", "")
                archive.writestr("resource_fixture/data.py", "")
                archive.writestr("resource_fixture/assets/one.json", "{}")
                archive.writestr("resource_fixture/assets/nested/two.json", "{}")
            environment = dict(os.environ, PYTHONPATH=str(output), PYTHONDONTWRITEBYTECODE="1")
            probe = subprocess.run([
                sys.executable, "-B", "-S", "-c",
                "import json, pathlib, sys; import packsanity_ut_adapter as a; "
                "import packsanity_ut_upstream as ut; "
                "from packsanity_ut_requirements import _requirement_is_available; "
                "assert _requirement_is_available('schema==0.7.8.0'); "
                "assert 'Generate' not in sys.modules and 'tracker' not in sys.modules; "
                "assert callable(ut.TMain) and callable(ut.replay_items); "
                "import worlds.AutoSNIClient, bsdiff4, requests, orjson, pkg_resources; "
                "from orjson import orjson as encoder; "
                "assert encoder.loads(orjson.dumps({'foo': 1})) == {'foo': 1}; "
                f"sys.path.insert(0, {str(resources)!r}); "
                "assert set(pkg_resources.resource_listdir('resource_fixture.data', 'assets')) == {'one.json', 'nested'}; "
                "assert pkg_resources.resource_isdir('resource_fixture.data', 'assets/nested'); "
                "assert not pkg_resources.resource_isdir('resource_fixture.data', 'assets/one.json'); "
                "assert all(callable(getattr(a, n)) for n in "
                "('load_world', 'load_tracker_package', 'initialize_runtime', 'update_state', 'shutdown_tracker')); "
                "print(json.dumps({n: m.__file__ for n, m in sys.modules.items() "
                "if n.startswith('packsanity_ut_')}))",
            ], cwd=fixture, env=environment, check=True, capture_output=True, text=True)
            loaded = json.loads(probe.stdout)
            self.assertIn("packsanity_ut_adapter", loaded)
            for module_path in loaded.values():
                self.assertTrue(Path(module_path).is_relative_to(output))
