from __future__ import annotations

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.collection_ops import empty_on
from lwnrdb._internal.descriptors import script_run, transaction, trigger_run, user
from lwnrdb._internal.response_decoder import boolean_of, documents
from lwnrdb.admin_types import (
    Permissions,
    ScriptRun,
    TransactionDecision,
    TransactionInfo,
    TriggerRun,
    TriggerRunDecision,
    TriggerRunStatus,
    UserInfo,
)
from lwnrdb.error_codes import ErrorCode
from lwnrdb.query.types import Filter, FilterStep, PipelineStep, require_filter


class AsyncUsers:
    def __init__(self, context: ClientContext) -> None:
        self._context = context

    async def create(self, username: str, password: str, permissions: Permissions | None = None) -> None:
        await self._context.channel.send(
            "CREATE_USER", self._context.scripts.create_user(username, password, permissions)
        )

    async def delete(self, username: str) -> None:
        await self._context.channel.send("DELETE_USER", self._context.scripts.delete_user(username))

    async def change_permissions(self, username: str, permissions: Permissions) -> None:
        await self._context.channel.send(
            "CHANGE_PERMISSIONS", self._context.scripts.change_permissions(username, permissions)
        )

    async def set_password(self, username: str, new_password: str) -> None:
        await self._context.channel.send(
            "SET_PASSWORD", self._context.scripts.set_password(username, None, new_password)
        )

    async def list(self, filter: Filter | None = None) -> list[UserInfo]:  # noqa: A002
        steps: tuple[PipelineStep, ...] = () if filter is None else (FilterStep(require_filter(filter, "filter")),)
        request = self._context.scripts.list_users(steps)

        async def read() -> list[UserInfo]:
            return [user(row) for row in documents(await self._context.channel.send("LIST_USERS", request), "users")]

        async def read_or_no_results() -> list[UserInfo]:
            return await empty_on(ErrorCode.NO_RESULTS, [], read)

        return await empty_on(ErrorCode.NO_USERS_FOUND, [], read_or_no_results)


class AsyncDistributedTransactions:
    def __init__(self, context: ClientContext) -> None:
        self._context = context

    async def list(self) -> list[TransactionInfo]:
        reply = await self._context.channel.send("LIST_TRANSACTIONS", self._context.scripts.list_transactions())
        return [transaction(row) for row in documents(reply, "transactions")]

    async def resolve(self, dtx_id: str, decision: TransactionDecision) -> None:
        await self._context.channel.send(
            "RESOLVE_TRANSACTION", self._context.scripts.resolve_transaction(dtx_id, decision)
        )


class AsyncScripts:
    def __init__(self, context: ClientContext) -> None:
        self._context = context

    async def list(self) -> list[ScriptRun]:
        reply = await self._context.channel.send("LIST_SCRIPTS", self._context.scripts.list_scripts())
        return [script_run(row) for row in documents(reply, "scripts")]

    async def cancel(self, run_id: str) -> bool:
        reply = await self._context.channel.send("CANCEL_SCRIPT", self._context.scripts.cancel_script(run_id))
        return boolean_of(reply.get("cancelled"), fallback=False)


class AsyncTriggerRuns:
    def __init__(self, context: ClientContext) -> None:
        self._context = context

    async def list(self, status: TriggerRunStatus | None = None) -> list[TriggerRun]:
        reply = await self._context.channel.send("LIST_TRIGGER_RUNS", self._context.scripts.list_trigger_runs(status))
        return [trigger_run(row) for row in documents(reply, "runs")]

    async def resolve(self, run_id: str, decision: TriggerRunDecision) -> bool:
        reply = await self._context.channel.send(
            "RESOLVE_TRIGGER_RUN", self._context.scripts.resolve_trigger_run(run_id, decision)
        )
        return boolean_of(reply.get("resolved"), fallback=False)


class AsyncAdmin:
    def __init__(self, context: ClientContext) -> None:
        self._context = context

    def users(self) -> AsyncUsers:
        return AsyncUsers(self._context)

    def transactions(self) -> AsyncDistributedTransactions:
        return AsyncDistributedTransactions(self._context)

    def scripts(self) -> AsyncScripts:
        return AsyncScripts(self._context)

    def trigger_runs(self) -> AsyncTriggerRuns:
        return AsyncTriggerRuns(self._context)
