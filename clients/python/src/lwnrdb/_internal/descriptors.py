from __future__ import annotations

from lwnrdb._internal.response_decoder import boolean_of, strings, text_of
from lwnrdb._internal.values_codec import JsonObject, decode_value
from lwnrdb.admin_types import ScriptRun, ServerInfo, TransactionInfo, TriggerRun, UserInfo
from lwnrdb.document import Document
from lwnrdb.results import ScriptResult
from lwnrdb.scripting_types import ProcedureInfo, ScheduleInfo, TriggerInfo


def _optional_text(row: Document, field: str) -> str | None:
    value = row.get(field)
    return value if isinstance(value, str) else None


def _text(row: Document, field: str) -> str:
    return _optional_text(row, field) or ""


def _optional_number(row: Document, field: str) -> float | None:
    value = row.get(field)
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return value


def _number(row: Document, field: str) -> float:
    number = _optional_number(row, field)
    return 0 if number is None else number


def _flag(row: Document, field: str, fallback: bool) -> bool:
    value = row.get(field)
    return value if isinstance(value, bool) else fallback


def _list(row: Document, field: str) -> tuple[str, ...]:
    value = row.get(field)
    return tuple(str(item) for item in value) if isinstance(value, list) else ()


def _grants(row: Document, field: str) -> Document:
    value = row.get(field)
    return value if isinstance(value, dict) else {}


def procedure(row: Document) -> ProcedureInfo:
    return ProcedureInfo(
        name=_text(row, "name"),
        version=_number(row, "version"),
        enabled=_flag(row, "enabled", fallback=True),
        description=_optional_text(row, "description"),
        source=_optional_text(row, "source"),
        raw=row,
    )


def trigger(row: Document) -> TriggerInfo:
    return TriggerInfo(
        name=_text(row, "name"),
        collection=_optional_text(row, "collection"),
        events=_list(row, "events"),
        procedure_name=_text(row, "procedureName"),
        timing=_optional_text(row, "timing") or "after",
        mode=_optional_text(row, "mode") or "document",
        enabled=_flag(row, "enabled", fallback=True),
        version=_number(row, "version"),
        definer=_optional_text(row, "definer"),
        raw=row,
    )


def schedule(row: Document) -> ScheduleInfo:
    return ScheduleInfo(
        name=_text(row, "name"),
        procedure_name=_text(row, "procedureName"),
        cron=_optional_text(row, "cron"),
        interval_ms=_optional_number(row, "intervalMs"),
        enabled=_flag(row, "enabled", fallback=True),
        version=_number(row, "version"),
        definer=_optional_text(row, "definer"),
        next_run_at=_optional_number(row, "nextRunAt"),
        raw=row,
    )


def user(row: Document) -> UserInfo:
    return UserInfo(
        username=_text(row, "_id"),
        admin=_flag(row, "admin", fallback=False),
        global_permissions=_list(row, "globalPermissions"),
        database_permissions=_grants(row, "databasePermissions"),
        collection_permissions=_grants(row, "collectionPermissions"),
        script_permissions=_grants(row, "scriptPermissions"),
        owned_databases=_list(row, "ownedDatabases"),
        raw=row,
    )


def transaction(row: Document) -> TransactionInfo:
    return TransactionInfo(
        dtx_id=_text(row, "dtxId"),
        coordinator=_optional_text(row, "coordinator"),
        coordinator_reachable=_flag(row, "coordinatorReachable", fallback=False),
        participants=_list(row, "participants"),
        age_ms=_number(row, "ageMs"),
        raw=row,
    )


def script_run(row: Document) -> ScriptRun:
    return ScriptRun(
        run_id=_text(row, "runId"),
        node=_optional_text(row, "node"),
        kind=_text(row, "kind"),
        database=_optional_text(row, "database"),
        name=_optional_text(row, "name"),
        username=_optional_text(row, "username"),
        age_ms=_number(row, "ageMs"),
        raw=row,
    )


def trigger_run(row: Document) -> TriggerRun:
    return TriggerRun(
        run_id=_text(row, "runId"),
        status=_text(row, "status"),
        database=_optional_text(row, "database"),
        collection=_optional_text(row, "collection"),
        trigger=_optional_text(row, "trigger"),
        procedure=_optional_text(row, "procedure"),
        event=_optional_text(row, "event"),
        attempts=_number(row, "attempts"),
        last_error=_optional_text(row, "lastError"),
        raw=row,
    )


def server(row: Document) -> ServerInfo:
    return ServerInfo(state=_optional_text(row, "state"), version=_optional_text(row, "version"), raw=row)


def script_result(reply: JsonObject) -> ScriptResult:
    result = reply.get("result")
    return ScriptResult(
        value=None if result is None else decode_value(result),
        logs=strings(reply.get("logs")),
        logs_truncated=boolean_of(reply.get("logsTruncated"), fallback=False),
        run_id=text_of(reply.get("runId")),
    )
