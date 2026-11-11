from __future__ import annotations

import sys
import tempfile
from pathlib import Path
from typing import Any

from hatchling.builders.config import BuilderConfig
from hatchling.builders.hooks.plugin.interface import BuildHookInterface

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "build_tools"))

from inject_docs import inject  # noqa: E402


class CustomBuildHook(BuildHookInterface[BuilderConfig]):
    PLUGIN_NAME = "custom"

    def initialize(self, version: str, build_data: dict[str, Any]) -> None:
        if self.target_name != "wheel" or version == "editable":
            return
        staging = Path(tempfile.mkdtemp(prefix="lwnrdb-docs-"))
        for relative, text in inject(ROOT / "src" / "lwnrdb", ROOT / "apidocs").items():
            target = staging / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
            build_data["force_include"][str(target)] = f"lwnrdb/{relative.as_posix()}"
