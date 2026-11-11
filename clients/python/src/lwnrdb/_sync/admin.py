from __future__ import annotations

from lwnrdb._async.admin import (
    AsyncAdmin,
    AsyncDistributedTransactions,
    AsyncScripts,
    AsyncTriggerRuns,
    AsyncUsers,
)
from lwnrdb._internal.portal import SyncRuntime
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
from lwnrdb.query.types import Filter


class Users:
    def __init__(self, runtime: SyncRuntime, inner: AsyncUsers) -> None:
        self._runtime = runtime
        self._inner = inner

    def create(self, username: str, password: str, permissions: Permissions | None = None) -> None:
        self._runtime.run(self._inner.create(username, password, permissions))

    def delete(self, username: str) -> None:
        self._runtime.run(self._inner.delete(username))

    def change_permissions(self, username: str, permissions: Permissions) -> None:
        self._runtime.run(self._inner.change_permissions(username, permissions))

    def set_password(self, username: str, new_password: str) -> None:
        self._runtime.run(self._inner.set_password(username, new_password))

    def list(self, filter: Filter | None = None) -> list[UserInfo]:  # noqa: A002
        return self._runtime.run(self._inner.list(filter))


class DistributedTransactions:
    def __init__(self, runtime: SyncRuntime, inner: AsyncDistributedTransactions) -> None:
        self._runtime = runtime
        self._inner = inner

    def list(self) -> list[TransactionInfo]:
        return self._runtime.run(self._inner.list())

    def resolve(self, dtx_id: str, decision: TransactionDecision) -> None:
        self._runtime.run(self._inner.resolve(dtx_id, decision))


class Scripts:
    def __init__(self, runtime: SyncRuntime, inner: AsyncScripts) -> None:
        self._runtime = runtime
        self._inner = inner

    def list(self) -> list[ScriptRun]:
        return self._runtime.run(self._inner.list())

    def cancel(self, run_id: str) -> bool:
        return self._runtime.run(self._inner.cancel(run_id))


class TriggerRuns:
    def __init__(self, runtime: SyncRuntime, inner: AsyncTriggerRuns) -> None:
        self._runtime = runtime
        self._inner = inner

    def list(self, status: TriggerRunStatus | None = None) -> list[TriggerRun]:
        return self._runtime.run(self._inner.list(status))

    def resolve(self, run_id: str, decision: TriggerRunDecision) -> bool:
        return self._runtime.run(self._inner.resolve(run_id, decision))


class Admin:
    def __init__(self, runtime: SyncRuntime, inner: AsyncAdmin) -> None:
        self._runtime = runtime
        self._inner = inner

    def users(self) -> Users:
        return Users(self._runtime, self._inner.users())

    def transactions(self) -> DistributedTransactions:
        return DistributedTransactions(self._runtime, self._inner.transactions())

    def scripts(self) -> Scripts:
        return Scripts(self._runtime, self._inner.scripts())

    def trigger_runs(self) -> TriggerRuns:
        return TriggerRuns(self._runtime, self._inner.trigger_runs())
