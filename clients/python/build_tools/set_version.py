from __future__ import annotations

import re
import sys
from pathlib import Path

PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"
VERSION_LINE = re.compile(r'^version = ".*"$', re.MULTILINE)
PEP_440 = re.compile(r"\d+(\.\d+)*((a|b|rc)\d+)?(\.post\d+)?(\.dev\d+)?")


def main(arguments: list[str]) -> int:
    if len(arguments) != 1 or PEP_440.fullmatch(arguments[0]) is None:
        sys.stderr.write("usage: set_version.py <PEP 440 version, such as 1.2.0 or 1.2.0rc1>\n")
        return 2
    text = PYPROJECT.read_text(encoding="utf-8")
    updated, count = VERSION_LINE.subn(f'version = "{arguments[0]}"', text, count=1)
    if count != 1:
        sys.stderr.write("pyproject.toml has no version line\n")
        return 1
    PYPROJECT.write_text(updated, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
