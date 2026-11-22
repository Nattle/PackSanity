"""Check APWorld requirements against the fixed runtime payload."""
from __future__ import annotations
from pathlib import Path
import zipfile

from packaging.requirements import InvalidRequirement, Requirement
from packaging.utils import canonicalize_name
from packsanity_runtime_dependencies import DEPENDENCIES
from packsanity_ut_models import TrackerAdapterError

BUNDLED_PYTHON_REQUIREMENTS = {canonicalize_name(name): version for name, version in DEPENDENCIES.items()}


def _read_requirements(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as archive:
            lines = {
                line.strip()
                for entry in archive.namelist()
                if entry.endswith("requirements.txt")
                for line in archive.read(entry).decode("utf-8").splitlines()
            }
    except (OSError, UnicodeDecodeError, zipfile.BadZipFile) as error:
        raise TrackerAdapterError(f"Could not inspect APWorld dependencies: {error}") from error
    return sorted((line for line in lines if line and not line.startswith("#")), key=str.lower)


def _requirement_is_available(requirement: str) -> bool:
    try:
        parsed = Requirement(requirement)
    except InvalidRequirement:
        return False
    version = BUNDLED_PYTHON_REQUIREMENTS.get(canonicalize_name(parsed.name))
    return (
        parsed.url is None
        and parsed.marker is None
        and version is not None
        and parsed.specifier.contains(version)
    )
