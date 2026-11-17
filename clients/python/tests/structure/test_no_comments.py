from __future__ import annotations

import ast
import io
import re
import tokenize
from pathlib import Path

from tests.structure.source_files import ROOT, source_files

DIRECTIVES = (
    re.compile(r"#\s*noqa\b"),
    re.compile(r"#\s*type:\s*ignore\b"),
    re.compile(r"#\s*pragma:\s*no cover\b"),
)


def _line_comments(path: Path, text: str) -> list[str]:
    return [
        f"{path.relative_to(ROOT)}:{token.start[0]} {token.string}"
        for token in tokenize.generate_tokens(io.StringIO(text).readline)
        if token.type == tokenize.COMMENT and not any(directive.match(token.string) for directive in DIRECTIVES)
    ]


def _docstrings(path: Path, text: str) -> list[str]:
    found = []
    tree = ast.parse(text)
    for node in [tree, *ast.walk(tree)]:
        if not isinstance(node, ast.Module | ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef) or not node.body:
            continue
        first = node.body[0]
        if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
            found.append(f"{path.relative_to(ROOT)}:{first.lineno} docstring")
    return found


def test_no_source_file_holds_a_comment_or_docstring() -> None:
    files = source_files()
    assert len(files) > 50
    problems = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        problems.extend(_line_comments(path, text))
        problems.extend(_docstrings(path, text))
    assert problems == []
