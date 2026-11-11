from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from enum import StrEnum

from lwnrdb.document import Document


class DataPermission(StrEnum):
    READ = "READ"
    READ_WRITE = "READ_WRITE"


class GlobalPermission(StrEnum):
    CREATE_DATABASE = "CREATE_DATABASE"
    DROP_DATABASE = "DROP_DATABASE"


class ScriptPermission(StrEnum):
    NONE = "NONE"
    RUN = "RUN"
    MANAGE = "MANAGE"


class TransactionDecision(StrEnum):
    COMMIT = "commit"
    ABORT = "abort"


class TriggerRunStatus(StrEnum):
    PENDING = "PENDING"
    DEAD = "DEAD"
    STAGED = "STAGED"


class TriggerRunDecision(StrEnum):
    REPLAY = "replay"
    DISCARD = "discard"


@dataclass(frozen=True, slots=True)
class Permissions:
    admin: bool = False
    global_permissions: Sequence[GlobalPermission] = ()
    databases: Mapping[str, DataPermission] = field(default_factory=dict)
    collections: Mapping[str, DataPermission] = field(default_factory=dict)
    scripts: Mapping[str, ScriptPermission] = field(default_factory=dict)


def collection_key(database: str, collection: str) -> str:
    return f"{database}|{collection}"


@dataclass(frozen=True, slots=True)
class UserInfo:
    username: str
    admin: bool
    global_permissions: tuple[str, ...]
    database_permissions: Document
    collection_permissions: Document
    script_permissions: Document
    owned_databases: tuple[str, ...]
    raw: Document


@dataclass(frozen=True, slots=True)
class TransactionInfo:
    dtx_id: str
    coordinator: str | None
    coordinator_reachable: bool
    participants: tuple[str, ...]
    age_ms: float
    raw: Document


@dataclass(frozen=True, slots=True)
class ScriptRun:
    run_id: str
    node: str | None
    kind: str
    database: str | None
    name: str | None
    username: str | None
    age_ms: float
    raw: Document


@dataclass(frozen=True, slots=True)
class TriggerRun:
    run_id: str
    status: str
    database: str | None
    collection: str | None
    trigger: str | None
    procedure: str | None
    event: str | None
    attempts: float
    last_error: str | None
    raw: Document


@dataclass(frozen=True, slots=True)
class ServerInfo:
    state: str | None
    version: str | None
    raw: Document
