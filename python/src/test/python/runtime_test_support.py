"""Prepare the pinned runtime for tests that import the adapter or stage a payload."""

from functools import cache
import os
from pathlib import Path
import subprocess
import sys


@cache
def staged_payload() -> Path:
    root = Path(__file__).resolve().parents[3]
    output = subprocess.check_output([
        os.environ.get("PACKSANITY_PYTHON", sys.executable),
        str(root / "tools/prepare_runtime.py"), "--payload-only",
    ], text=True, timeout=1800)
    return Path(output.strip())
