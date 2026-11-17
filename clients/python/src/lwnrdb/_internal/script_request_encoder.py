from __future__ import annotations

from collections.abc import Mapping, Sequence

from lwnrdb._internal.request_encoder import RequestEncoder, request
from lwnrdb._internal.values_codec import Json, JsonObject
from lwnrdb.admin_types import Permissions, TransactionDecision, TriggerRunDecision, TriggerRunStatus
from lwnrdb.query.types import PipelineStep
from lwnrdb.scripting_types import ProcedureDefinition, ScheduleDefinition, TriggerDefinition, TriggerEvent


def _optional(**fields: Json) -> JsonObject:
    return {name: value for name, value in fields.items() if value is not None}


def _grants(granted: Mapping[str, str]) -> JsonObject:
    return {key: str(value) for key, value in granted.items()}


def _permissions(granted: Permissions) -> JsonObject:
    global_permissions: list[Json] = [str(permission) for permission in granted.global_permissions]
    return {
        "admin": granted.admin,
        "globalPermissions": global_permissions,
        "databasePermissions": _grants(granted.databases),
        "collectionPermissions": _grants(granted.collections),
        "scriptPermissions": _grants(granted.scripts),
    }


def _text(value: str | None) -> str | None:
    return None if value is None else str(value)


class ScriptRequestEncoder:
    __slots__ = ("_encoder",)

    def __init__(self, encoder: RequestEncoder) -> None:
        self._encoder = encoder

    def run_script(self, database: str, script: str, args: object) -> JsonObject:
        return {**request("RUN_SCRIPT", database), "script": script, **self._args(args)}

    def save_procedure(self, database: str, procedure: ProcedureDefinition) -> JsonObject:
        return {
            **request("SAVE_PROCEDURE", database),
            "name": procedure.name,
            "script": procedure.script,
            **_optional(description=procedure.description, enabled=procedure.enabled, ifVersion=procedure.if_version),
        }

    def delete_procedure(self, database: str, name: str) -> JsonObject:
        return {**request("DELETE_PROCEDURE", database), "name": name}

    def list_procedures(self, database: str, include_source: bool | None) -> JsonObject:
        return {**request("LIST_PROCEDURES", database), **_optional(includeSource=include_source)}

    def call_procedure(self, database: str, name: str, args: object) -> JsonObject:
        return {**request("CALL_PROCEDURE", database), "procedureName": name, **self._args(args)}

    def save_trigger(self, database: str, collection: str, trigger: TriggerDefinition) -> JsonObject:
        if not trigger.events:
            raise ValueError("a trigger needs at least one event")
        events: list[Json] = [str(event) for event in trigger.events]
        return {
            **request("SAVE_TRIGGER", database, collection),
            "name": trigger.name,
            "events": events,
            "procedureName": trigger.procedure_name,
            **_optional(
                timing=_text(trigger.timing),
                mode=_text(trigger.mode),
                allowCascade=trigger.allow_cascade,
                enabled=trigger.enabled,
                ifVersion=trigger.if_version,
            ),
        }

    def delete_trigger(self, database: str, collection: str, name: str) -> JsonObject:
        return {**request("DELETE_TRIGGER", database, collection), "name": name}

    def list_triggers(self, database: str, collection: str | None) -> JsonObject:
        return request("LIST_TRIGGERS", database, collection)

    def test_trigger(
        self,
        database: str,
        collection: str,
        name: str,
        event: TriggerEvent,
        document: object,
    ) -> JsonObject:
        return {
            **request("TEST_TRIGGER", database, collection),
            "name": name,
            "event": str(event),
            "document": self._encoder.document(document),
        }

    def save_schedule(self, database: str, schedule: ScheduleDefinition) -> JsonObject:
        if (schedule.cron is None) == (schedule.interval_ms is None):
            raise ValueError("a schedule takes exactly one of cron and interval_ms")
        return {
            **request("SAVE_SCHEDULE", database),
            "name": schedule.name,
            "procedureName": schedule.procedure_name,
            **_optional(cron=schedule.cron, intervalMs=schedule.interval_ms),
            **self._args(schedule.args),
            **_optional(
                timeoutMs=schedule.timeout_ms,
                description=schedule.description,
                enabled=schedule.enabled,
                ifVersion=schedule.if_version,
            ),
        }

    def delete_schedule(self, database: str, name: str) -> JsonObject:
        return {**request("DELETE_SCHEDULE", database), "name": name}

    def list_schedules(self, database: str, include_args: bool | None) -> JsonObject:
        return {**request("LIST_SCHEDULES", database), **_optional(includeArgs=include_args)}

    def list_scripts(self) -> JsonObject:
        return request("LIST_SCRIPTS")

    def cancel_script(self, run_id: str) -> JsonObject:
        return {**request("CANCEL_SCRIPT"), "runId": run_id}

    def list_trigger_runs(self, status: TriggerRunStatus | None) -> JsonObject:
        return {**request("LIST_TRIGGER_RUNS"), **_optional(status=_text(status))}

    def resolve_trigger_run(self, run_id: str, decision: TriggerRunDecision) -> JsonObject:
        return {**request("RESOLVE_TRIGGER_RUN"), "runId": run_id, "decision": str(decision)}

    def list_transactions(self) -> JsonObject:
        return request("LIST_TRANSACTIONS")

    def resolve_transaction(self, dtx_id: str, decision: TransactionDecision) -> JsonObject:
        return {**request("RESOLVE_TRANSACTION"), "dtxId": dtx_id, "decision": str(decision)}

    def create_user(self, username: str, password: str, granted: Permissions | None) -> JsonObject:
        frame = {**request("CREATE_USER"), "username": username, "password": password}
        return frame if granted is None else {**frame, **_permissions(granted)}

    def delete_user(self, username: str) -> JsonObject:
        return {**request("DELETE_USER"), "username": username}

    def change_permissions(self, username: str, granted: Permissions) -> JsonObject:
        return {**request("CHANGE_PERMISSIONS"), "username": username, **_permissions(granted)}

    def set_password(self, username: str, current_password: str | None, new_password: str) -> JsonObject:
        return {
            **request("SET_PASSWORD"),
            "username": username,
            **_optional(currentPassword=current_password),
            "newPassword": new_password,
        }

    def list_users(self, steps: Sequence[PipelineStep]) -> JsonObject:
        if not steps:
            return request("LIST_USERS")
        return {**request("LIST_USERS"), "aggregationSteps": self._encoder.pipeline.steps(steps)}

    def _args(self, args: object) -> JsonObject:
        return {} if args is None else {"args": self._encoder.document(args)}
