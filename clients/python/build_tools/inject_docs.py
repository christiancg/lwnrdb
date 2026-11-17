from __future__ import annotations

import ast
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = ROOT / "src" / "lwnrdb"
APIDOCS = ROOT / "apidocs"
FUNCTIONS_DOC = "functions"
_DUNDERS = frozenset({"__enter__", "__exit__", "__aenter__", "__aexit__"})
_SYNC_ALIASES = {"__enter__": "__aenter__", "__exit__": "__aexit__"}
ASYNC_PREFIX = "Async"


class DocsError(Exception):
    pass


@dataclass
class ApiDoc:
    summary: str
    members: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class Insertion:
    line: int
    indent: int
    text: str


def parse_api_doc(text: str) -> ApiDoc:
    summary: list[str] = []
    sections: dict[str, list[str]] = {}
    current = summary
    for line in text.splitlines():
        if line.startswith("## "):
            current = []
            sections[line[3:].strip()] = current
        elif not line.startswith("# "):
            current.append(line)
    return ApiDoc("\n".join(summary).strip(), {name: "\n".join(lines).strip() for name, lines in sections.items()})


def read_api_docs(folder: Path) -> dict[str, ApiDoc]:
    return {path.stem: parse_api_doc(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*.md"))}


def exported_names(package: Path) -> list[str]:
    tree = ast.parse((package / "__init__.py").read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "__all__" for target in node.targets
        ):
            value = ast.literal_eval(node.value)
            return [str(name) for name in value]
    raise DocsError("lwnrdb/__init__.py has no __all__")


def _body_on_own_line(node: ast.FunctionDef | ast.AsyncFunctionDef, lines: list[str]) -> bool:
    first = node.body[0]
    return lines[first.lineno - 1][: first.col_offset].strip() == ""


def _insertion(node: ast.FunctionDef | ast.AsyncFunctionDef | ast.ClassDef, text: str) -> Insertion:
    first = node.body[0]
    return Insertion(first.lineno, first.col_offset, text)


def _member_insertions(node: ast.ClassDef, doc: ApiDoc, lines: list[str], problems: list[str]) -> list[Insertion]:
    insertions: list[Insertion] = []
    seen: set[str] = set()
    for member in node.body:
        if not isinstance(member, ast.FunctionDef | ast.AsyncFunctionDef) or not _is_member(member, lines):
            continue
        seen.add(_SYNC_ALIASES.get(member.name, member.name) if member.name not in doc.members else member.name)
        text = doc.members.get(member.name) or doc.members.get(_SYNC_ALIASES.get(member.name, ""))
        if text is None:
            problems.append(f"{node.name}.{member.name} has no '## {member.name}' section")
        else:
            insertions.append(_insertion(member, text))
    stale = [name for name in doc.members if name not in seen]
    problems.extend(f"the apidocs of {node.name} have a stale section '## {name}'" for name in stale)
    return insertions


def _is_member(member: ast.FunctionDef | ast.AsyncFunctionDef, lines: list[str]) -> bool:
    if member.name.startswith("_") and member.name not in _DUNDERS:
        return False
    if any(isinstance(decorator, ast.Name) and decorator.id == "overload" for decorator in member.decorator_list):
        return False
    return _body_on_own_line(member, lines)


def _splice(source: str, insertions: list[Insertion]) -> str:
    lines = source.splitlines(keepends=True)
    for insertion in sorted(insertions, key=lambda item: item.line, reverse=True):
        indent = " " * insertion.indent
        escaped = insertion.text.replace("\\", "\\\\").replace('"""', '\\"\\"\\"')
        body = escaped.replace("\n", "\n" + indent).replace(indent + "\n", "\n")
        lines.insert(insertion.line - 1, f'{indent}"""{body}\n{indent}"""\n')
    return "".join(lines)


@dataclass
class _Plan:
    insertions: dict[Path, list[Insertion]] = field(default_factory=dict)
    documented: set[str] = field(default_factory=set)
    problems: list[str] = field(default_factory=list)

    def add(self, path: Path, insertion: Insertion) -> None:
        self.insertions.setdefault(path, []).append(insertion)


def _doc_name(name: str, docs: dict[str, ApiDoc]) -> str | None:
    if name in docs:
        return name
    async_name = ASYNC_PREFIX + name
    return async_name if async_name in docs else None


def _visit_module(path: Path, exported: set[str], docs: dict[str, ApiDoc], plan: _Plan) -> None:
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source)
    lines = source.splitlines()
    functions = docs.get(FUNCTIONS_DOC, ApiDoc(""))
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name in exported:
            doc_name = _doc_name(node.name, docs)
            if doc_name is None:
                plan.problems.append(f"{node.name} has no apidocs/{node.name}.md")
                continue
            doc = docs[doc_name]
            plan.documented.add(doc_name)
            plan.add(path, _insertion(node, doc.summary))
            for insertion in _member_insertions(node, doc, lines, plan.problems):
                plan.add(path, insertion)
        elif isinstance(node, ast.FunctionDef) and node.name in exported:
            text = functions.members.get(node.name)
            if text is None:
                plan.problems.append(f"{node.name} has no '## {node.name}' section in apidocs/{FUNCTIONS_DOC}.md")
                continue
            plan.documented.add(node.name)
            plan.add(path, _insertion(node, text))


def inject(package: Path = PACKAGE, apidocs: Path = APIDOCS) -> dict[Path, str]:
    docs = read_api_docs(apidocs)
    exported = set(exported_names(package))
    plan = _Plan()
    for path in sorted(package.rglob("*.py")):
        _visit_module(path, exported, docs, plan)
    plan.problems.extend(
        f"apidocs/{name}.md documents nothing the package exports"
        for name in docs
        if name != FUNCTIONS_DOC and name not in plan.documented
    )
    plan.problems.extend(
        f"apidocs/{FUNCTIONS_DOC}.md has a stale section '## {name}'"
        for name in docs.get(FUNCTIONS_DOC, ApiDoc("")).members
        if name not in plan.documented
    )
    if plan.problems:
        raise DocsError("the API docs are out of date:\n  " + "\n  ".join(sorted(plan.problems)))
    return {
        path.relative_to(package): _splice(path.read_text(encoding="utf-8"), insertions)
        for path, insertions in plan.insertions.items()
    }


def main() -> int:
    try:
        injected = inject()
    except DocsError as error:
        sys.stderr.write(f"{error}\n")
        return 1
    sys.stdout.write(f"API docs complete: {len(injected)} modules receive docstrings\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
