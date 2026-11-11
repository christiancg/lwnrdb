from __future__ import annotations

import sys

import lwnrdb
from tests.structure.source_files import ROOT

sys.path.insert(0, str(ROOT / "build_tools"))

from inject_docs import inject


def test_every_exported_name_resolves_once() -> None:
    assert len(lwnrdb.__all__) == len(set(lwnrdb.__all__))
    assert [name for name in lwnrdb.__all__ if not hasattr(lwnrdb, name)] == []


def test_every_public_class_and_function_has_api_docs() -> None:
    injected = inject()
    assert injected
