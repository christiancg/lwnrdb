from __future__ import annotations

import ast

from tests.structure.source_files import ROOT, source_files

MAX_FILE_LINES = 500
MAX_FUNCTION_LINES = 200


def test_no_file_is_longer_than_500_lines() -> None:
    oversized = [
        f"{path.relative_to(ROOT)} has {count} lines"
        for path in source_files()
        if (count := len(path.read_text(encoding="utf-8").splitlines())) > MAX_FILE_LINES
    ]
    assert oversized == []


def test_no_function_is_longer_than_200_lines() -> None:
    oversized = []
    for path in source_files():
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
                length = (node.end_lineno or node.lineno) - node.lineno + 1
                if length > MAX_FUNCTION_LINES:
                    oversized.append(f"{path.relative_to(ROOT)}:{node.lineno} {node.name} has {length} lines")
    assert oversized == []
