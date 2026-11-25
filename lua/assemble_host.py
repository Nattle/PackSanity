"""Assemble the plain C host bootstrap from its ordered Lua fragments."""
from pathlib import Path
import sys


def main() -> None:
    output, *fragments = map(Path, sys.argv[1:])
    source = "".join(fragment.read_text(encoding="utf-8") for fragment in fragments)
    output.write_text(source.replace("__PACKSANITY_EPOCH_SECONDS__", "0"), encoding="utf-8")


if __name__ == "__main__":
    main()
