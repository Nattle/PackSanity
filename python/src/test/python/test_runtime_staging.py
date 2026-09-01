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


class RuntimeStagingTest(unittest.TestCase):
    def test_staged_payload_imports_adapter_without_the_source_tree_or_bytecode(self):
        root = Path(__file__).parents[3]
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory)
            core = fixture / "core"
            python = fixture / "python"
            dependencies = fixture / "dependencies"
            output = fixture / "output"
            sources = {
                "BaseClasses.py": "CollectionState = ItemClassification = LocationProgressType = MultiWorld = object\n",
                "worlds/AutoWorld.py": "class World: pass\n",
                "worlds/LauncherComponents.py": "from Launcher import launch\n",
                "worlds/generic/Rules.py": "def exclusion_rules(*args): pass\n",
            }
            for name in (
                "NetUtils.py", "Options.py", "Utils.py", "settings.py", "Fill.py", "worlds/Files.py",
                "entrance_rando.py", "LICENSE",
                "worlds/_bizhawk/__init__.py", "rule_builder/__init__.py", "worlds/generic/__init__.py",
            ):
                sources[name] = ""
            for name, source in sources.items():
                path = core / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
            for name in (
                "yaml/__init__.py", "schema/__init__.py", "typing_extensions.py", "pathspec/__init__.py",
                "pyyaml-6.0.3.dist-info/licenses/LICENSE", "schema-0.7.8.dist-info/licenses/LICENSE-MIT",
                "typing_extensions-4.15.0.dist-info/licenses/LICENSE", "pathspec-1.0.4.dist-info/licenses/LICENSE",
            ):
                path = dependencies / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("")
            python.mkdir()
            (python / "LICENSE").write_text("CPython license fixture")
            imports = fixture / "imports.json"
            imports.write_text("[]")
            subprocess.run([
                sys.executable, "-B", str(root / "tools/stage_runtime.py"),
                "--archipelago-source", str(core), "--cpython-source", str(python),
                "--site-packages", str(dependencies), "--imports", str(imports), "--output", str(output),
            ], check=True, capture_output=True, text=True)
            self.assertEqual(
                json.loads((root / "runtime.lock.json").read_text())["runtimeId"],
                (output / "packsanity-runtime-id.txt").read_text(),
            )
            source_modules = {path.name for path in (root / "src/main/python").glob("*.py")}
            self.assertTrue(source_modules.issubset({path.name for path in output.glob("*.py")}))
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
                "import json, pathlib, sys; import packsanity_ut_adapter as a; import worlds.LauncherComponents, bsdiff4, requests, orjson, pkg_resources; "
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
