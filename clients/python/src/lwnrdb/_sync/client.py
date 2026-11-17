from __future__ import annotations

import contextlib
from collections.abc import Iterator
from contextlib import AbstractContextManager, contextmanager
from types import TracebackType
from typing import Self

from lwnrdb._async.client import AsyncLwnrdbClient
from lwnrdb._internal.connection_factory import Opener, open_streams
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb._internal.request_channel import Backoff, jittered_backoff
from lwnrdb._sync.admin import Admin
from lwnrdb._sync.database import Database
from lwnrdb._sync.transaction import Transaction
from lwnrdb.admin_types import ServerInfo
from lwnrdb.document import Document
from lwnrdb.settings import ClientSettings


class LwnrdbClient:
    def __init__(self, runtime: SyncRuntime, inner: AsyncLwnrdbClient) -> None:
        self._runtime = runtime
        self._inner = inner

    @classmethod
    def connect(cls, settings: ClientSettings | None = None) -> LwnrdbClient:
        return cls._connect(settings or ClientSettings(), jittered_backoff, open_streams)

    @classmethod
    def _connect(cls, settings: ClientSettings, backoff: Backoff, opener: Opener) -> LwnrdbClient:
        runtime = SyncRuntime()
        try:
            inner = runtime.run(AsyncLwnrdbClient._connect(settings, backoff, opener))
        except BaseException:
            runtime.stop(settings.shutdown_timeout)
            raise
        return cls(runtime, inner)

    @property
    def settings(self) -> ClientSettings:
        return self._inner.settings

    def database(self, name: str) -> Database:
        return Database(self._runtime, self._inner.database(name))

    def list_databases(self) -> tuple[str, ...]:
        return self._runtime.run(self._inner.list_databases())

    def server_info(self) -> ServerInfo:
        return self._runtime.run(self._inner.server_info())

    def database_stats(self) -> Document:
        return self._runtime.run(self._inner.database_stats())

    def change_password(self, current_password: str, new_password: str) -> None:
        self._runtime.run(self._inner.change_password(current_password, new_password))

    def admin(self) -> Admin:
        return Admin(self._runtime, self._inner.admin())

    def begin_transaction(self) -> Transaction:
        return Transaction(self._runtime, self._runtime.run(self._inner.begin_transaction()))

    def transaction(self) -> AbstractContextManager[Transaction]:
        return self._transaction()

    @contextmanager
    def _transaction(self) -> Iterator[Transaction]:
        transaction = self.begin_transaction()
        try:
            yield transaction
        except BaseException:
            if transaction.is_open:
                with contextlib.suppress(Exception):
                    transaction.rollback()
            raise
        if transaction.is_open:
            transaction.commit()

    def close(self) -> None:
        if not self._runtime.portal.is_running:
            return
        try:
            self._runtime.run(self._inner.close())
        finally:
            self._runtime.stop(self._inner.settings.shutdown_timeout)

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()
