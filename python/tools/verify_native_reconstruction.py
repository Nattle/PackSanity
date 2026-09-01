#!/usr/bin/env python3
"""Replay real APWorld fixtures through production C calls in a single process.

Archives are supplied locally and SHA-verified. Fixture generation is validation-only;
PackSanity itself only reconstructs the server's graph and never generates a seed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


# This probe runs in an isolated pinned Python process with only the staged mobile payload.
FIXTURE_PROBE = '''
import json, sys
from packsanity_ut_adapter import MobileUniversalTracker
tracker = MobileUniversalTracker()
archive, root, game, options = sys.argv[1:]
tracker.load_world(archive, root)
mw = tracker._build_multiworld(game, 'Native fixture', json.loads(options), None)
world = mw.worlds[1]
ids = sorted(loc.address for loc in mw.get_locations(1) if isinstance(loc.address, int))
items = [{'item_id': item.code, 'flags': int(item.classification),
          'location_id': -1, 'player': 1} for item in mw.itempool if item.code is not None][:3]
checked = ids[:max(0, len(ids) - 68)]
print(json.dumps({'initialize': {'game': game, 'slot': 1, 'slot_name': 'Native fixture',
                   'slot_data': world.fill_slot_data()},
                 'update': {'received_items': items, 'checked_locations': checked,
                            'missing_locations': [i for i in ids if i not in checked]},
                 'expected_ids': ids}))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--modules', type=Path, required=True)
    parser.add_argument('--archives', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    args.modules = args.modules.resolve()
    args.archives = args.archives.resolve()
    fixtures = json.loads((root / 'src/test/fixtures/reconstruction-archives.json').read_text())
    lock = json.loads((root / 'runtime.lock.json').read_text())
    runtime_id = (args.modules / 'packsanity-runtime-id.txt').read_text().strip()
    if runtime_id != lock['runtimeId']:
        raise RuntimeError(f'Staged runtime ID {runtime_id} does not match {lock["runtimeId"]}')
    sys.path.insert(0, str(root / 'src/test/python'))
    from test_native_bridge import compile_probe
    python = Path(sys.executable)
    python_version = subprocess.check_output([str(python), '--version'], text=True).strip()
    if python_version != f'Python {lock["cpython"]["version"]}':
        raise RuntimeError(f'Host interpreter {python_version} does not match the runtime lock')
    environment = dict(os.environ, PYTHONPATH=str(args.modules))
    cases = []
    # Interleave games, then replace each archive with another version in the same process.
    by_root = {}
    for fixture in fixtures:
        by_root.setdefault(fixture['packageRoot'], []).append(fixture)
    ordered = [fixture for batch in zip(*by_root.values()) for fixture in batch]
    if len(ordered) != len(fixtures):
        raise RuntimeError('Fixture groups must contain the same number of archives')
    for fixture in ordered:
        archive = args.archives / fixture['archiveName']
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != fixture['sha256']:
            raise RuntimeError(f'Archive SHA-256 mismatch: {archive}')
        for options in fixture['options']:
            probe = subprocess.run(
                [str(python), '-B', '-S', '-c', FIXTURE_PROBE, str(archive),
                 fixture['packageRoot'], fixture['game'], json.dumps(options)],
                env=environment, check=True, capture_output=True, text=True, timeout=60,
            )
            cases.append((fixture, options, json.loads(probe.stdout), archive))
    with tempfile.TemporaryDirectory() as directory:
        binary, home = compile_probe(root, Path(directory), stream=True)
        commands = []
        for fixture, options, case, archive in cases:
            late_update = dict(case['update'])
            late_update['slot_data'] = dict(case['initialize']['slot_data'], packsanity_validation_metadata=1)
            late_update['checked_locations'] = case['update']['checked_locations'] + case['update']['missing_locations'][:1]
            late_update['missing_locations'] = case['update']['missing_locations'][1:]
            late_update['received_items'] = case['update']['received_items'] + case['update']['received_items'][:1]
            # Model a seed whose membership is a strict subset of the reconstructed graph.
            # Completing the last check must not bring the reconstructed-only ID back.
            complete = {
                **late_update, 'checked_locations': case['expected_ids'][:-1], 'missing_locations': [],
            }
            incomplete = {**complete, 'missing_locations': [2**53 - 1]}
            commands.extend([
                'worker:create:0',
                'load:0:' + json.dumps({'apworld_path': str(archive), 'package_root': fixture['packageRoot']}),
                'worker:initialize:0:' + json.dumps(case['initialize']),
                'update:0:' + json.dumps(case['update']),
                'worker:update:0:' + json.dumps(late_update),
                'update:0:' + json.dumps(complete),
                'worker:update:0:' + json.dumps(incomplete),
            ])
            alternate = next(
                (other for _, _, other, path in cases
                 if path == archive and other['initialize']['slot_data'] != case['initialize']['slot_data']),
                case,
            )
            # Rebuild to another real slot-data profile in the same runtime. Compare it
            # with that profile initialized from scratch elsewhere in the replay.
            rebuild = {**alternate['update'], 'slot_data': alternate['initialize']['slot_data']}
            commands.extend(['worker:update:0:' + json.dumps(rebuild), 'worker:close:0'])
        # Two different worlds stay open while one rejects a replacement slot snapshot.
        # A subsequent valid replay must recover without changing the other runtime.
        overlap_start = len(commands)
        for handle, (fixture, _, case, archive) in enumerate(cases[:2]):
            commands.extend([
                f'worker:create:{handle}',
                f'load:{handle}:' + json.dumps({'apworld_path': str(archive), 'package_root': fixture['packageRoot']}),
                f'worker:initialize:{handle}:' + json.dumps(case['initialize']),
                f'update:{handle}:' + json.dumps(case['update']),
            ])
        failed_update_at = len(commands)
        commands.extend([
            'worker:update:0:' + json.dumps({**cases[0][2]['update'], 'slot_data': []}),
            'worker:update:1:' + json.dumps(cases[1][2]['update']),
            'worker:update:0:' + json.dumps({
                **cases[0][2]['update'],
                'slot_data': dict(cases[0][2]['initialize']['slot_data'], packsanity_retry_validation=1),
            }),
            'worker:close:0',
            'update:1:' + json.dumps(cases[1][2]['update']),
            'worker:close:1',
        ])
        native_environment = dict(os.environ)
        native_environment.pop('PYTHONPATH', None)
        probe = subprocess.run([str(binary), str(home), str(args.modules)],
                               input="\n".join(commands) + "\n",
                               env=native_environment, capture_output=True, text=True, timeout=120)
        if probe.returncode != 0:
            raise RuntimeError(f'Native bridge exited {probe.returncode}: {probe.stderr}')
        lines = probe.stdout.splitlines()
        if len(lines) != len(commands):
            raise RuntimeError(f'Expected {len(commands)} bridge responses, got {len(lines)}')
        assert lines[failed_update_at].startswith('ERROR '), lines[failed_update_at]
        assert 'Slot data must be an object' in lines[failed_update_at], lines[failed_update_at]
        failures = [line for index, line in enumerate(lines)
                    if index != failed_update_at and not line.startswith('OK ')]
        if failures:
            raise RuntimeError('Native reconstruction failed: ' + '\n'.join(failures))
        report = []
        replies = [json.loads(line[3:]) if line.startswith("OK {") else None for line in lines]
        def snapshot(index):
            response = replies[index]
            # Both absent and null map fields indicate a mapless world at the bridge.
            return dict(response, map=response.get('map'))

        assert snapshot(overlap_start + 7) == snapshot(failed_update_at + 1) == snapshot(failed_update_at + 4)
        assert snapshot(overlap_start + 3) == snapshot(failed_update_at + 2)
        for index, (fixture, options, case, archive) in enumerate(cases):
            initialization, state, late_state, complete, incomplete, rebuilt = replies[index * 9 + 2:index * 9 + 8]
            assert initialization['game'] == fixture['game']
            assert state['game'] == fixture['game']
            actual_ids = sorted(loc['id'] for loc in state['locations'])
            assert actual_ids == case['expected_ids'], f'Server check mismatch: {archive.name} {options}'
            checked = sum(loc['checked'] for loc in state['locations'])
            assert checked == len(case['update']['checked_locations'])
            assert sum(item['count'] for item in state['inventory']) == len(case['update']['received_items'])
            assert sorted(loc['id'] for loc in late_state['locations']) == case['expected_ids']
            assert sum(loc['checked'] for loc in late_state['locations']) == checked + 1
            assert sum(item['count'] for item in late_state['inventory']) == len(case['update']['received_items']) + 1
            expected_complete = set(case['expected_ids'][:-1])
            assert {row['id'] for row in complete['locations']} == expected_complete
            assert all(row['checked'] for row in complete['locations'])
            assert {row['id'] for row in incomplete['locations']} == expected_complete | {2**53 - 1}
            server_only = next(row for row in incomplete['locations'] if row['id'] == 2**53 - 1)
            assert not server_only['checked'] and server_only['reachable'] is None
            alternate_index = next(
                (i for i, (_, _, other, path) in enumerate(cases)
                 if path == archive and other['initialize']['slot_data'] != case['initialize']['slot_data']),
                index,
            )
            fresh = replies[alternate_index * 9 + 3]
            for key in ('locations', 'inventory', 'regions', 'entrances'):
                assert rebuilt[key] == fresh[key], f'Rebuilt {key} differed from fresh {archive.name} {options}'
            report.append({'archive': archive.name, 'sha256': fixture['sha256'], 'options': options,
                           'checks': len(actual_ids), 'checked': checked, 'late_update_checked': checked + 1,
                           'rebuild_options': cases[alternate_index][1], 'complete_subset': len(expected_complete),
                           'server_only_id': 2**53 - 1,
                           'passed': True})
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps({
        'python': lock['cpython']['version'],
        'runtimeId': runtime_id,
        'adapter': lock['adapter']['version'],
        'overlap_and_failed_update_recovery': True,
        'cases': report,
    }, indent=2) + '\n')
    print(f'Passed {len(report)} native reconstruction cases; report: {args.report}')


if __name__ == '__main__':
    main()
