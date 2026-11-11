from __future__ import annotations

from dataclasses import dataclass

from lwnrdb.document import Document, Value


@dataclass(frozen=True, slots=True)
class SaveResult:
    id: str | None
    inserted: bool


@dataclass(frozen=True, slots=True)
class BulkSaveResult:
    inserted: tuple[str, ...]
    updated: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class ReindexResult:
    rebuilt_fields: tuple[str, ...]
    primary_key_entries: float | None
    unversioned_entries: float | None


@dataclass(frozen=True, slots=True)
class ScriptResult:
    value: Value
    logs: tuple[str, ...]
    logs_truncated: bool
    run_id: str | None


@dataclass(frozen=True, slots=True)
class AnalyzeResult:
    results: tuple[Document, ...]
    analysis: Document
