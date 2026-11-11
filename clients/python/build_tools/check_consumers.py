from __future__ import annotations

import subprocess
import sys
import tempfile
import venv
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONSUMERS = ROOT / "consumers"
MYPY_PIN = "mypy==2.4.1"


def _run(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def _wheel(dist: Path) -> Path:
    wheels = sorted(dist.glob("lwnrdb_client-*.whl"))
    if not wheels:
        raise SystemExit(f"no lwnrdb_client wheel in {dist}; run python -m build first")
    return wheels[-1]


def main(arguments: list[str]) -> int:
    dist = Path(arguments[0]) if arguments else ROOT / "dist"
    wheel = _wheel(dist)
    with tempfile.TemporaryDirectory(prefix="lwnrdb-consumers-") as folder:
        environment = Path(folder) / "venv"
        venv.create(environment, with_pip=True)
        python = str(environment / ("Scripts" if sys.platform == "win32" else "bin") / "python")
        _run([python, "-m", "pip", "install", "--quiet", str(wheel), MYPY_PIN], Path(folder))
        _run([python, "-I", str(CONSUMERS / "sync_app" / "main.py")], Path(folder))
        _run([python, "-I", str(CONSUMERS / "async_app" / "main.py")], Path(folder))
        typed = CONSUMERS / "typed"
        _run([python, "-m", "mypy", "--config-file", str(typed / "mypy.ini"), str(typed / "main.py")], Path(folder))
        sys.stdout.write("typed consumer ok\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
