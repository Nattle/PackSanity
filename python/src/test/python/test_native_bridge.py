"""Exercise the production C bridge in a separate process, including interpreter lifetime."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import sysconfig
import tempfile
import unittest


ADAPTER = '''import json
_loaded = None
_updates = 0

def load_world(request):
    global _loaded
    name = json.loads(request)['package_root']
    if _loaded is not None and _loaded != name:
        raise RuntimeError('An APWorld is already loaded. Create a new interpreter before switching worlds.')
    _loaded = name
    return json.dumps({'world': _loaded, 'updates': _updates})

def initialize_runtime(request):
    if json.loads(request).get('fail'):
        raise ValueError('generation failed')
    return json.dumps({'world': _loaded})

def update_state(request):
    global _updates
    _updates += 1
    return json.dumps({'world': _loaded, 'updates': _updates})

def shutdown_tracker():
    pass  # Just like the real adapter, session shutdown retains APWorld registration.
'''


def compile_probe(root: Path, output: Path, stream: bool = False) -> tuple[Path, Path]:
    """Compile against the host Python, or the pinned CPython build when explicitly supplied."""
    includes = [Path(sysconfig.get_path('include'))]
    library = Path(sysconfig.get_config_var('LIBDIR')) / sysconfig.get_config_var('LDLIBRARY')
    home = Path(sys.base_prefix)
    framework = sysconfig.get_config_var('PYTHONFRAMEWORK')
    if not library.is_file() and framework:
        library = home / framework
    native = root.parent / 'native'
    source_name = 'native_bridge_stream_probe' if stream else 'native_bridge_probe'
    binary = output / source_name
    flags = ['-Wl,--export-dynamic'] if sys.platform.startswith('linux') else []
    command = shlex.split(os.environ.get('CC', 'cc')) + [
        '-Wall', '-Wextra', '-Werror=return-type', *flags,
        '-I', str(native / 'include'),
        *[arg for directory in includes for arg in ('-I', str(directory))],
        str(root / f'src/test/cpp/{source_name}.c'),
        str(native / 'src/python_host.c'), str(library),
        '-Wl,-rpath,' + str(library.parent), '-pthread',
        *shlex.split(sysconfig.get_config_var('LIBS') or ''),
        *shlex.split(sysconfig.get_config_var('SYSLIBS') or ''),
        '-o', str(binary),
    ]
    subprocess.run(command, check=True, capture_output=True, text=True)
    return binary, home


class NativeBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.output = Path(cls.directory.name)
        cls.root = Path(__file__).resolve().parents[3]
        cls.binary, cls.home = compile_probe(cls.root, cls.output)
        cls.modules = cls.output / 'modules'
        cls.modules.mkdir()
        (cls.modules / 'packsanity_ut_adapter.py').write_text(ADAPTER)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def probe(self, *commands):
        environment = dict(os.environ)
        environment.pop('PYTHONPATH', None)
        result = subprocess.run(
            [str(self.binary), str(self.home), str(self.modules), *commands],
            env=environment, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def test_switching_worlds_after_close_uses_fresh_registration(self):
        lines = self.probe('create:0', 'load:0:{"package_root":"librarian"}', 'update:0:{}',
                           'worker:close:0', 'worker:create:0', 'load:0:{"package_root":"ttyd"}',
                           'worker:close:0', 'create:0', 'worker:load:0:{"package_root":"librarian"}')
        self.assertEqual('OK {"world": "ttyd", "updates": 0}', lines[5])
        self.assertEqual('OK {"world": "librarian", "updates": 0}', lines[-1])

    def test_live_runtimes_do_not_share_modules_or_shutdown(self):
        lines = self.probe('create:0', 'worker:create:1', 'load:0:{"package_root":"librarian"}',
                           'worker:load:1:{"package_root":"ttyd"}', 'update:0:{}',
                           'worker:update:1:{}', 'close:0', 'worker:update:1:{}')
        self.assertEqual('OK {"world": "ttyd", "updates": 1}', lines[5])
        self.assertEqual('OK {"world": "ttyd", "updates": 2}', lines[-1])

    def test_reconnect_does_not_retain_previous_session_state(self):
        lines = self.probe('create:0', 'load:0:{"package_root":"librarian"}', 'update:0:{}',
                           'close:0', 'create:0', 'load:0:{"package_root":"librarian"}')
        self.assertEqual('OK {"world": "librarian", "updates": 0}', lines[-1])

    def test_generation_failure_can_be_closed_and_replaced(self):
        lines = self.probe('create:0', 'load:0:{"package_root":"librarian"}',
                           'worker:initialize:0:{"fail":true}', 'close:0', 'create:0',
                           'worker:load:0:{"package_root":"ttyd"}', 'initialize:0:{}')
        self.assertEqual('ERROR generation failed', lines[2])
        self.assertEqual('OK {"world": "ttyd"}', lines[-1])

    def test_import_failure_does_not_poison_next_interpreter(self):
        broken = self.output / 'broken'
        broken.mkdir(exist_ok=True)
        (broken / 'packsanity_ut_adapter.py').write_text("raise ValueError('broken adapter')")
        lines = self.probe('worker:create:0:' + str(broken), 'create:0',
                           'worker:load:0:{"package_root":"librarian"}')
        self.assertEqual('ERROR broken adapter', lines[0])
        self.assertEqual('OK {"world": "librarian", "updates": 0}', lines[-1])

    def test_closed_handle_reports_an_error_without_crashing(self):
        lines = self.probe('create:0', 'close:0', 'worker:update:0:{}', 'close:0')
        self.assertTrue(lines[2].startswith('ERROR Universal Tracker runtime is closed'))
        self.assertEqual('OK null', lines[-1])

    def test_stream_accepts_large_server_payload_and_switches_threads(self):
        output = self.output / 'stream'
        output.mkdir()
        binary, home = compile_probe(self.root, output, stream=True)
        request = json.dumps({'package_root': 'large_world', 'server_data': 'x' * 200_000})
        commands = ['worker:create:0', 'load:0:' + request, 'worker:update:0:{}',
                    'close:0', 'create:0', 'worker:load:0:{"package_root":"next_world"}']
        environment = dict(os.environ)
        environment.pop('PYTHONPATH', None)
        result = subprocess.run(
            [str(binary), str(home), str(self.modules)],
            input='\n'.join(commands) + '\n', env=environment,
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(len(commands), len(lines))
        self.assertEqual('OK {"world": "large_world", "updates": 1}', lines[2])
        self.assertEqual('OK {"world": "next_world", "updates": 0}', lines[-1])


if __name__ == '__main__':
    unittest.main()
