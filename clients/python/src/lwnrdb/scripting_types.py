from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from lwnrdb.document import Document


class TriggerEvent(StrEnum):
    CREATED = "CREATED"
    UPDATED = "UPDATED"
    DELETED = "DELETED"


class TriggerTiming(StrEnum):
    AFTER = "after"
    BEFORE = "before"


class TriggerMode(StrEnum):
    DOCUMENT = "document"
    BATCH = "batch"


@dataclass(frozen=True, slots=True)
class ProcedureDefinition:
    name: str
    script: str
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class TriggerDefinition:
    name: str
    procedure_name: str
    events: Sequence[TriggerEvent]
    timing: TriggerTiming | None = None
    mode: TriggerMode | None = None
    allow_cascade: bool | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class ScheduleDefinition:
    name: str
    procedure_name: str
    cron: str | None = None
    interval_ms: int | None = None
    args: Document | None = None
    timeout_ms: int | None = None
    description: str | None = None
    enabled: bool | None = None
    if_version: int | None = None


@dataclass(frozen=True, slots=True)
class ProcedureInfo:
    name: str
    version: float
    enabled: bool
    description: str | None
    source: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class TriggerInfo:
    name: str
    collection: str | None
    events: tuple[str, ...]
    procedure_name: str
    timing: str
    mode: str
    enabled: bool
    version: float
    definer: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class ScheduleInfo:
    name: str
    procedure_name: str
    cron: str | None
    interval_ms: float | None
    enabled: bool
    version: float
    definer: str | None
    next_run_at: float | None
    raw: Document


@dataclass(frozen=True, slots=True)
class TriggerSaved:
    version: float
    definer: str


@dataclass(frozen=True, slots=True)
class TriggerTestResult:
    decision: str
    document: Document | None
    logs: tuple[str, ...]
    logs_truncated: bool
