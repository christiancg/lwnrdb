from __future__ import annotations

from types import TracebackType
from typing import Self

from lwnrdb._async.subscription import AsyncSubscription
from lwnrdb._internal.portal import SyncRuntime


class Subscription:
    def __init__(self, runtime: SyncRuntime, inner: AsyncSubscription) -> None:
        self._runtime = runtime
        self._inner = inner

    @property
    def listen_id(self) -> str:
        return self._inner.listen_id

    @property
    def is_active(self) -> bool:
        return self._inner.is_active

    def close(self) -> None:
        self._runtime.run(self._inner.close())

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()
