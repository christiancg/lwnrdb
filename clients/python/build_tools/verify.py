from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _steps(dist: Path) -> list[tuple[str, list[str]]]:
    python = sys.executable
    return [
        ("format", [python, "-m", "ruff", "format", "--check", "."]),
        ("lint", [python, "-m", "ruff", "check", "."]),
        ("types", [python, "-m", "mypy"]),
        ("catalogue", [python, "build_tools/catalogue.py", "--check"]),
        ("unit tests and coverage", [python, "-m", "pytest", "--cov", "--cov-report=term-missing"]),
        ("build", [python, "-m", "build", "--outdir", str(dist), "."]),
        ("consumers", [python, "build_tools/check_consumers.py", str(dist)]),
    ]


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="lwnrdb-dist-") as folder:
        for name, command in _steps(Path(folder)):
            sys.stdout.write(f"==> {name}\n")
            sys.stdout.flush()
            if subprocess.run(command, cwd=ROOT, check=False).returncode != 0:
                sys.stderr.write(f"verify failed at: {name}\n")
                return 1
    sys.stdout.write("verify passed\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
