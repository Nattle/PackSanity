#!/usr/bin/env python3
"""Regression checks for the published pack-runner surface."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile


def run_pack(source: Path, host: Path, bootstrap: Path) -> None:
    command = [
        sys.executable,
        str(Path(__file__).parents[1] / "tools" / "test_pack.py"),
        str(source),
        "--host",
        str(host),
        "--bootstrap",
        str(bootstrap),
        "--json",
    ]
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    if '"status": "boot_passed"' not in completed.stdout:
        raise AssertionError(completed.stdout)


def main() -> int:
    fixture, host, bootstrap = map(Path, sys.argv[1:4])
    with tempfile.TemporaryDirectory(prefix="packsanity-test-") as directory:
        archive = Path(directory) / "fixture.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for source in fixture.rglob("*"):
                if source.is_file():
                    output.write(source, Path("archive-root") / source.relative_to(fixture))
        run_pack(archive, host, bootstrap)

        duplicate_items = Path(directory) / "duplicate-items"
        (duplicate_items / "items").mkdir(parents=True)
        (duplicate_items / "scripts").mkdir()
        (duplicate_items / "manifest.json").write_text(
            '{"package_uid":"duplicate-items"}', encoding="utf-8"
        )
        (duplicate_items / "items" / "active.json").write_text(
            '[{"name":"Active","type":"static","codes":"small_key"}]', encoding="utf-8"
        )
        (duplicate_items / "items" / "alternate.json").write_text(
            '[{"name":"Alternate","type":"consumable","codes":"small_key"}]', encoding="utf-8"
        )
        (duplicate_items / "scripts" / "init.lua").write_text(
            "Tracker:AddItems('items/active.json')\n"
            "assert(Tracker:ProviderCountForCode('small_key') == 1)\n",
            encoding="utf-8",
        )
        run_pack(duplicate_items, host, bootstrap)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
