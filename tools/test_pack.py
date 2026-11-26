#!/usr/bin/env python3
from __future__ import annotations
"""Run a tracker pack's Lua entrypoint against the PackSanity compatibility host."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

MAX_ARCHIVE_BYTES = 256 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ENTRIES = 8192
MAX_ENTRY_BYTES = 256 * 1024 * 1024
# This runner is a host compatibility harness rather than an application-specific mobile policy.
# Consumers that need the mobile policy can select its 24 MiB PackIO budget explicitly.
DEFAULT_PACK_IO_BYTES = 96 * 1024 * 1024
OMITTED_EXTENSIONS = {"png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "mp3", "ogg", "wav", "flac", "mp4", "webm", "avi"}
UNAVAILABLE_APIS = {
    "AutoTracker:AddMemoryWatch", "AutoTracker:ReadU8", "AutoTracker:ReadU16",
    "AutoTracker:ReadU24", "AutoTracker:ReadU32", "AutoTracker:ReadVariable",
    "ScriptHost:AddMemoryWatch", "ScriptHost:AddVariableWatch",
}


class PackError(RuntimeError):
    pass


def normalized_path(value: str) -> str:
    value = value.replace("\\", "/").strip()
    if not value or value.startswith("/") or re.match(r"^[A-Za-z]:", value):
        raise PackError(f"unsafe archive path: {value!r}")
    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise PackError(f"unsafe archive path: {value!r}")
    return value


def remove_jsonc(source: str) -> str:
    """Remove comments and extra commas while preserving string values and token boundaries."""
    output = []
    index = 0
    comma = None
    while index < len(source):
        char = source[index]
        if char == '"':
            start = index
            index += 1
            while index < len(source):
                if source[index] == '"':
                    index += 1
                    break
                index += 2 if source[index] == "\\" else 1
            output.append(source[start:index])
            comma = None
            continue
        if source.startswith("//", index):
            end = source.find("\n", index + 2)
            index = len(source) if end < 0 else end
            continue
        if source.startswith("/*", index):
            end = source.find("*/", index + 2)
            if end < 0:
                raise PackError("unterminated JSONC block comment")
            output.append(" " + "\n" * source.count("\n", index, end + 2))
            index = end + 2
            continue
        if char == ',':
            if comma is not None:
                output[comma] = ""
            comma = len(output)
        elif not char.isspace():
            if comma is not None and char in "}]":
                output[comma] = ""
            comma = None
        output.append(char)
        index += 1
    return "".join(output)


def read_archive(source: str) -> tuple[dict[str, bytes], str]:
    path = Path(source)
    if path.is_dir():
        files = {}
        for candidate in path.rglob("*"):
            if candidate.is_file():
                files[normalized_path(candidate.relative_to(path).as_posix())] = candidate.read_bytes()
        return files, str(path)
    if not path.is_file():
        raise PackError(f"source does not exist: {source}")
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        raise PackError(f"archive exceeds {MAX_ARCHIVE_BYTES} byte compressed limit")
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > MAX_ENTRIES:
            raise PackError(f"archive has more than {MAX_ENTRIES} entries")
        files, expanded = {}, 0
        for info in infos:
            if info.is_dir():
                continue
            if stat.S_ISLNK(info.external_attr >> 16):
                raise PackError(f"archive contains a symbolic link: {info.filename}")
            relative = normalized_path(info.filename)
            if info.file_size > MAX_ENTRY_BYTES:
                raise PackError(f"archive entry is too large: {relative}")
            expanded += info.file_size
            if expanded > MAX_UNCOMPRESSED_BYTES:
                raise PackError("archive exceeds uncompressed size limit")
            files[relative] = archive.read(info)
        return files, str(path)


def resolve_root(files: dict[str, bytes]) -> dict[str, bytes]:
    # A pack-root manifest is authoritative. Nested manifests are commonly schemas or script data.
    if "manifest.json" in files:
        return files
    manifests = [path for path in files if path == "manifest.json" or path.endswith("/manifest.json")]
    shallowest_depth = min((manifest.count("/") for manifest in manifests), default=-1)
    roots = [manifest for manifest in manifests if manifest.count("/") == shallowest_depth]
    if len(roots) != 1:
        raise PackError(f"expected one shallowest manifest.json, found {len(roots)}")
    manifest = roots[0]
    root = manifest.rsplit("/", 1)[0] + "/" if "/" in manifest else ""
    result = {path.removeprefix(root): contents for path, contents in files.items() if path.startswith(root)}
    if "manifest.json" not in result:
        raise PackError("could not resolve pack root")
    return result


def select_variant(files: dict[str, bytes], requested: str | None) -> tuple[str, dict[str, bytes], dict]:
    try:
        manifest = json.loads(remove_jsonc(files["manifest.json"].decode("utf-8-sig")))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackError(f"invalid manifest.json: {error}") from error
    if not isinstance(manifest.get("package_uid"), str) or not manifest["package_uid"]:
        raise PackError("manifest.json must contain a non-empty package_uid")
    variants = manifest.get("variants") or {}
    if not isinstance(variants, dict):
        raise PackError("manifest variants must be an object")
    if requested is not None and requested not in (variants or {"standard": {}}):
        raise PackError(f"unknown variant: {requested}")
    active = requested or ("standard" if "standard" in variants else next(iter(variants), "standard"))
    selected, overlay = {}, {}
    for path, contents in files.items():
        first, separator, remaining = path.partition("/")
        if separator and first in variants:
            if first == active:
                overlay[remaining] = contents
        else:
            selected[path] = contents
    return active, selected | overlay, manifest


def api_warnings(files: dict[str, bytes]) -> list[str]:
    warnings = set()
    for path, contents in files.items():
        if not path.endswith(".lua"):
            continue
        text = contents.decode("utf-8", errors="replace")
        for api in UNAVAILABLE_APIS:
            if api in text:
                warnings.add(f"{path}: {api} is an intentionally unavailable device-autotracking API")
    return sorted(warnings)


def lua_string(value: object) -> str:
    text = str(value)
    escaped = []
    for character in text:
        if character == "\\":
            escaped.append("\\\\")
        elif character == '"':
            escaped.append('\\"')
        elif character == "\n":
            escaped.append("\\n")
        elif character == "\r":
            escaped.append("\\r")
        elif character == "\t":
            escaped.append("\\t")
        elif ord(character) < 32:
            escaped.append(f"\\{ord(character):03d}")
        elif ord(character) > 126:
            escaped.append(f"\\u{{{ord(character):x}}}")
        else:
            escaped.append(character)
    return '"' + "".join(escaped) + '"'


def lua_strings(values: list[str]) -> str:
    return "{" + ",".join(lua_string(value) for value in values) + "}"


def json_value(value: object, default: int = 0) -> int:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return default
    return default


def json_bool(value: object, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str) and value.lower() in {"true", "false"}:
        return value.lower() == "true"
    return default


def codes(value: object) -> list[str]:
    if isinstance(value, str):
        return [code.strip() for code in value.split(",") if code.strip()]
    if isinstance(value, list):
        return [code for item in value for code in codes(item)]
    return []


def slug(value: str) -> str:
    return re.sub(r"(^-+|-+$)", "", re.sub(r"[^a-z0-9]+", "-", value.lower())) or "unnamed"


def read_json(contents: bytes) -> object:
    try:
        return json.loads(remove_jsonc(contents.decode("utf-8-sig")))
    except (UnicodeDecodeError, json.JSONDecodeError, PackError):
        return None


def item_seed_source(path: str, records: list[dict]) -> list[str]:
    statements = []
    for index, item in enumerate(records):
        if not isinstance(item, dict):
            continue
        item_type = item.get("type") if isinstance(item.get("type"), str) else "unknown"
        base_codes = codes(item.get("codes"))
        stages = item.get("stages") if isinstance(item.get("stages"), list) else []
        stage_codes = [] if item_type == "composite_toggle" else [code for stage in stages if isinstance(stage, dict) for code in codes(stage.get("codes"))]
        all_codes = list(dict.fromkeys(base_codes + stage_codes))
        name = item.get("name") if isinstance(item.get("name"), str) else f"Item {index}"
        item_id = all_codes[0] if all_codes else slug(name)
        stage_table = []
        for stage in stages:
            if isinstance(stage, dict):
                stage_table.append("{codes=" + lua_strings(codes(stage.get("codes"))) + ",inherit=" + ("true" if json_bool(stage.get("inherit_codes"), True) else "false") + "}")
        max_quantity = json_value(item.get("max_quantity"), -1)
        initially_active = json_bool(item.get("initial_active_state"), False)
        allow_disabled = json_bool(item.get("allow_disabled"), True)
        arguments = [
            lua_string(path), lua_string(item_id), lua_string(item_type), str(max_quantity),
            "true" if initially_active else "false", "true" if allow_disabled else "false",
            str(json_value(item.get("initial_stage_idx"))), str(json_value(item.get("initial_quantity"))),
            str(json_value(item.get("min_quantity"))), str(json_value(item.get("increment"), 1)),
            str(json_value(item.get("decrement"), 1)), lua_string(item.get("img") if isinstance(item.get("img"), str) else ""),
            lua_strings(base_codes), "{" + ",".join(stage_table) + "}",
            "true" if json_bool(item.get("loop"), False) or item_type == "progressive_toggle" else "false",
            lua_string(item["base_item"]) if isinstance(item.get("base_item"), str) else "nil",
            lua_string(item["item_left"]) if isinstance(item.get("item_left"), str) else "nil",
            lua_string(item["item_right"]) if isinstance(item.get("item_right"), str) else "nil",
        ]
        statements.append("PackSanityStageItem(" + ",".join(arguments) + ")")
    return statements


def location_seed_source(path: str, records: list[dict]) -> list[str]:
    statements: list[str] = []

    def visit(location: dict, parents: list[str], ordinal: int) -> None:
        name = location.get("name") if isinstance(location.get("name"), str) else f"Location {ordinal}"
        location_path = parents + [name]
        location_id = "location/" + "/".join(slug(part) for part in location_path)
        sections = location.get("sections") if isinstance(location.get("sections"), list) else []
        for section_index, section in enumerate(sections):
            if not isinstance(section, dict):
                continue
            section_name = section.get("name") if isinstance(section.get("name"), str) else f"Section {section_index}"
            reference = section.get("ref") if isinstance(section.get("ref"), str) else section_name
            aliases = ["@" + "/".join(location_path + [section_name])]
            if reference:
                aliases.append(reference if reference.startswith("@") else "@" + reference)
            arguments = [
                lua_string(path), lua_string(location_id + "/" + slug(reference)),
                str(json_value(section.get("item_count"), 0 if section.get("hosted_item") else 1)),
                lua_strings(list(dict.fromkeys(aliases))),
                "true" if json_bool(section.get("clear_as_group"), False) else "false",
                lua_strings(codes(section.get("hosted_item"))),
            ]
            statements.append("PackSanityStageLocation(" + ",".join(arguments) + ")")
        # The host exposes a location-level lookup even when it has sections.
        statements.append("PackSanityStageLocation(" + ",".join([lua_string(path), lua_string(location_id), "0", lua_strings(["@" + "/".join(location_path)]), "false", "{}"] ) + ")")
        children = location.get("children") if isinstance(location.get("children"), list) else []
        for child_index, child in enumerate(children):
            if isinstance(child, dict):
                visit(child, location_path, child_index)

    for index, location in enumerate(records):
        if isinstance(location, dict):
            visit(location, [], index)
    return statements


def lua_table(entries) -> str:
    return "{" + ",".join(f"[{lua_string(key)}]={value}" for key, value in entries) + "}"


def path_key(path: str) -> str:
    return path.lower().replace("ς", "σ")


def seed_source(files: dict[str, bytes]) -> str:
    statements = []
    shapes = []
    folded_paths = {}
    for path, contents in sorted(files.items()):
        folded_paths.setdefault(path_key(path), path)
        start = contents.removeprefix(b"\xef\xbb\xbf").lstrip()[:1]
        if start not in (b"[", b"{", b"/"):
            continue
        parsed = read_json(contents)
        if isinstance(parsed, dict):
            shapes.append((path, "false"))
        if not isinstance(parsed, list):
            continue
        shapes.append((path, "true"))
        records = [record for record in parsed if isinstance(record, dict)]
        folder = path.split("/", 1)[0].lower()
        if any(
            record.keys() & {"codes", "stages", "item_left", "item_right", "max_quantity"}
            or isinstance(record.get("type"), str)
            or isinstance(record.get("name"), str) and folder in {"items", "items.json"}
            for record in records
        ):
            statements.extend(item_seed_source(path, records))
        if any(
            record.keys() & {"sections", "children", "map_locations", "parent", "access_rules", "visibility_rules"}
            or isinstance(record.get("name"), str) and (len(record) == 1 or folder in {"locations", "locations.json"})
            for record in records
        ):
            statements.extend(location_seed_source(path, records))
    max_code_point = 0x10FFFF if any(any(ord(char) > 0xFFFF for char in path) for path in files) else 0xFFFF
    case_keys = lua_table(
        (character, lua_string(key))
        for character in map(chr, range(0x80, max_code_point + 1))
        if (key := path_key(character)) != character
    )
    exact_paths = lua_table((path, "true") for path in files)
    folded_lookup = lua_table((key, lua_string(path)) for key, path in folded_paths.items())
    lookup_layers = "{{" + exact_paths + "," + folded_lookup + "}}"
    setup = [
        f"PackSanitySetDefinitionCaseKeys({case_keys})",
        f"PackSanitySetDefinitionPaths('', {lookup_layers}, {lua_table(shapes)})",
    ]
    return "\n".join(setup + statements) + "\n"


def run(files: dict[str, bytes], host: str, bootstrap: str, pack_io_limit: int, variant: str) -> list[str]:
    with tempfile.TemporaryDirectory(prefix="packsanity-pack-") as temp:
        root = Path(temp)
        total = 0
        skipped = []
        modules = []
        command = [host, "--bootstrap", bootstrap]
        for path, contents in sorted(files.items()):
            extension = path.rsplit(".", 1)[-1].lower() if "." in path else ""
            if extension in OMITTED_EXTENSIONS:
                continue
            if extension != "lua" and total + len(contents) > pack_io_limit:
                skipped.append(path)
                continue
            total += len(contents)
            destination = root / "pack" / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(contents)
            command.extend(["--pack-file", f"{path}={destination}"])
            if extension == "lua":
                modules.append(f"PackSanityRegisterPackModule({lua_string(path)})")
        staging = root / "packsanity_seed.lua"
        staging.write_text(seed_source(files), encoding="utf-8")
        startup = root / "packsanity_startup.lua"
        startup.write_text("\n".join([
            f"Tracker.ActiveVariantUID = {lua_string(variant)}",
            *modules,
            "assert(ScriptHost:LoadScript('scripts/init.lua'), __packsanity_last_load_error)",
        ]), encoding="utf-8")
        command.extend(["--execute", str(staging), "--execute", str(startup)])
        completed = subprocess.run(command, capture_output=True, text=True)
        if completed.returncode:
            stderr = completed.stderr.strip().splitlines()
            stdout = completed.stdout.strip().splitlines()
            context = stdout[-1] if stdout else None
            error = stderr[-1] if stderr else None
            diagnostic = " | ".join(part for part in (context, error) if part)
            raise PackError(diagnostic or "packsanity-lua exited without a diagnostic")
        return [f"PackIO omitted {len(skipped)} non-Lua files after the {pack_io_limit} byte limit" ] if skipped else []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="a local pack directory or ZIP file")
    parser.add_argument("--variant", help="variant UID; defaults to standard then the first manifest variant")
    parser.add_argument("--host", default=os.environ.get("PACKSANITY_LUA", "packsanity-lua"), help="packsanity-lua executable")
    parser.add_argument("--bootstrap", default=str(Path(__file__).resolve().parents[1] / "build" / "meson" / "host.lua"))
    parser.add_argument("--pack-io-limit", type=int, default=DEFAULT_PACK_IO_BYTES, help="maximum non-media PackIO bytes registered with Lua (default: 96 MiB)")
    parser.add_argument("--json", action="store_true", help="emit a machine-readable report")
    options = parser.parse_args()
    try:
        host = options.host if Path(options.host).is_file() else shutil.which(options.host)
        if host is None:
            raise PackError("packsanity-lua was not found; build with Meson or pass --host")
        files, source = read_archive(options.source)
        root_files = resolve_root(files)
        variant, selected, manifest = select_variant(root_files, options.variant)
        if options.pack_io_limit <= 0:
            raise PackError("--pack-io-limit must be positive")
        warnings = api_warnings(selected)
        warnings.extend(run(selected, host, options.bootstrap, options.pack_io_limit, variant))
        report = {"status": "boot_passed", "source": source, "package_uid": manifest["package_uid"], "variant": variant, "warnings": warnings}
        print(json.dumps(report, sort_keys=True) if options.json else f"PASS {manifest['package_uid']} (variant: {variant})")
        for warning in warnings:
            print(f"WARN {warning}", file=sys.stderr)
        return 0
    except (PackError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"FAIL {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
