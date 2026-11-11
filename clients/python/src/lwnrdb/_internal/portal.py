from __future__ import annotations

import asyncio
import contextlib
import threading
from collections.abc import Coroutine
from concurrent.futures import ThreadPoolExecutor
from typing import Any, TypeVar

from lwnrdb.errors import LwnrdbError

T = TypeVar("T")


class Portal:
    def __init__(self, name: str = "lwnrdb-client") -> None:
        self._loop = asyncio.new_event_loop()
        self._stopped = False
        self._thread = threading.Thread(target=self._loop.run_forever, name=name, daemon=True)
        self._thread.start()

    @property
    def thread(self) -> threading.Thread:
        return self._thread

    @property
    def is_running(self) -> bool:
        return not self._stopped

    def run(self, work: Coroutine[Any, Any, T]) -> T:
        if self._stopped:
            work.close()
            raise LwnrdbError("the client is closed")
        if threading.current_thread() is self._thread:
            work.close()
            raise LwnrdbError("a sync client cannot be called from its own event loop")
        future = asyncio.run_coroutine_threadsafe(work, self._loop)
        try:
            return future.result()
        except BaseException:
            future.cancel()
            raise

    def stop(self, seconds: float) -> None:
        if self._stopped:
            return
        self._stopped = True
        with contextlib.suppress(Exception):
            asyncio.run_coroutine_threadsafe(_cancel_remaining(), self._loop).result(seconds)
        self._loop.call_soon_threadsafe(self._loop.stop)
        if threading.current_thread() is not self._thread:
            self._thread.join(seconds)
        if not self._thread.is_alive():
            self._loop.close()


async def _cancel_remaining() -> None:
    current = asyncio.current_task()
    remaining = [task for task in asyncio.all_tasks() if task is not current]
    for task in remaining:
        task.cancel()
    await asyncio.gather(*remaining, return_exceptions=True)


class SyncRuntime:
    def __init__(self) -> None:
        self.portal = Portal()
        self.listen_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="lwnrdb-listen")

    def run(self, work: Coroutine[Any, Any, T]) -> T:
        return self.portal.run(work)

    def stop(self, seconds: float) -> None:
        self.portal.stop(seconds)
        self.listen_executor.shutdown(wait=False, cancel_futures=True)
