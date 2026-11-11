from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCANNED_FOLDERS = ("src", "tests", "build_tools", "consumers")
SCANNED_FILES = ("hatch_build.py",)
SKIPPED_FOLDERS = frozenset({".venv", "dist", "build", "__pycache__", ".mypy_cache", ".pytest_cache", ".ruff_cache"})


def _python_files(folder: Path) -> list[Path]:
    if not folder.exists():
        return []
    return [
        path for path in sorted(folder.rglob("*.py")) if not SKIPPED_FOLDERS.intersection(path.relative_to(ROOT).parts)
    ]


def source_files() -> list[Path]:
    files = [path for folder in SCANNED_FOLDERS for path in _python_files(ROOT / folder)]
    return files + [ROOT / name for name in SCANNED_FILES if (ROOT / name).exists()]
