"""Fixed mobile dependency compatibility gate; never queries or installs host packages."""
from __future__ import annotations
from pathlib import Path
import re
import zipfile
from packsanity_ut_models import TrackerAdapterError

# Fixed runtime manifest; update with staging and runtime.lock.json.
BUNDLED_PYTHON_REQUIREMENTS = {
    "pyyaml": "6.0.3",
    "schema": "0.7.8",
    "typing-extensions": "4.15.0",
    "pathspec": "1.0.4",
}


def _read_requirements(path: Path) -> list[str]:
    """Return normalized declared requirements without ever invoking a package manager.

    An APWorld archive is executable code, so this is only an early compatibility gate; its
    imports still remain authoritative.  Direct URLs, editable installs, and requirement
    includes cannot be safely satisfied by the fixed mobile runtime and are rejected here.
    """
    try:
        with zipfile.ZipFile(path) as archive:
            requirement_entries = [
                name for name in archive.namelist() if name.rstrip("/").endswith("requirements.txt")
            ]
            lines = [
                line.strip()
                for entry in requirement_entries
                for line in archive.read(entry).decode("utf-8").splitlines()
            ]
    except (OSError, UnicodeDecodeError, zipfile.BadZipFile) as error:
        raise TrackerAdapterError(f"Could not inspect APWorld dependencies: {error}") from error

    requirements = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if line.startswith(("-r", "--requirement", "-e", "--editable")) or "://" in line or "@" in line:
            requirements.append(line)
            continue
        requirements.append(line)
    return sorted(set(requirements), key=str.lower)


def _requirement_is_available(requirement: str) -> bool:
    match = re.match(r"^([A-Za-z0-9_.-]+)(?:\[[^]]+\])?\s*(.*)$", requirement)
    if match is None:
        return False
    name, specifier = match.groups()
    normalized_name = re.sub(r"[-_.]+", "-", name).lower()
    bundled_version = BUNDLED_PYTHON_REQUIREMENTS.get(normalized_name)
    if bundled_version is None:
        return False
    return _version_satisfies(bundled_version, specifier)


def _version_satisfies(version: str, specifier: str) -> bool:
    if not specifier:
        return True
    candidate = _version_parts(version)
    for part in specifier.split(","):
        match = re.fullmatch(r"\s*(===|==|!=|>=|<=|>|<|~=)\s*([0-9][A-Za-z0-9_.*-]*)\s*", part)
        if match is None:
            return False
        operator, requested = match.groups()
        required = _version_parts(requested)
        wildcard = requested.endswith(".*")
        if wildcard:
            required = _version_parts(requested[:-2])
        if operator in {"===", "=="} and (
            candidate[: len(required)] != required if wildcard else candidate != required
        ):
            return False
        if operator == "!=" and (
            candidate[: len(required)] == required if wildcard else candidate == required
        ):
            return False
        if wildcard:
            if operator not in {"==", "!="}:
                return False
            continue
        if operator == ">=" and candidate < required:
            return False
        if operator == "<=" and candidate > required:
            return False
        if operator == ">" and candidate <= required:
            return False
        if operator == "<" and candidate >= required:
            return False
        # PEP 440 compatible-release ranges are deliberately supported only for numeric
        # versions: ~=X.Y means >=X.Y and <(X+1), while ~=X.Y.Z means <X.(Y+1).
        if operator == "~=" and (
            candidate < required
            or len(required) < 2
            or candidate >= required[:-2] + (required[-2] + 1,)
        ):
            return False
    return True


def _version_parts(value: str) -> tuple[int, ...]:
    parts = []
    for part in re.split(r"[._-]", value):
        numeric = re.match(r"\d+", part)
        if numeric is None:
            break
        parts.append(int(numeric.group()))
    return tuple(parts)
