from __future__ import annotations

import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import pytest

from lwnrdb import ListenEndedError, ListenUpdate, LwnrdbError
from lwnrdb._internal.handler_adapter import async_callbacks, threaded_callbacks
from lwnrdb._internal.portal import Portal, SyncRuntime


def test_run_returns_values_and_raises_errors() -> None:
    portal = Portal()

    async def answer() -> int:
        return 42

    async def fail() -> None:
        raise ValueError("boom")

    assert portal.run(answer()) == 42
    with pytest.raises(ValueError, match="boom"):
        portal.run(fail())
    assert portal.thread.daemon
    portal.stop(2)
    assert not portal.thread.is_alive()
    assert not portal.is_running
    portal.stop(2)


def test_run_after_stop_is_refused() -> None:
    portal = Portal()
    portal.stop(2)

    async def work() -> None:
        return None

    with pytest.raises(LwnrdbError, match="closed"):
        portal.run(work())


def test_run_from_the_loop_thread_is_refused() -> None:
    portal = Portal()

    async def nested() -> str:
        async def inner() -> None:
            return None

        try:
            portal.run(inner())
        except LwnrdbError as error:
            return str(error)
        return "ran"

    assert "own event loop" in portal.run(nested())
    portal.stop(2)


def test_an_interrupted_wait_cancels_the_coroutine() -> None:
    portal = Portal()
    started = threading.Event()
    cancelled = threading.Event()

    async def slow() -> None:
        started.set()
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            cancelled.set()
            raise

    outcome: list[BaseException] = []

    def caller() -> None:
        try:
            portal.run(slow())
        except BaseException as error:
            outcome.append(error)

    worker = threading.Thread(target=caller)
    worker.start()
    started.wait(5)
    portal.stop(5)
    worker.join(5)
    assert cancelled.is_set()
    assert outcome


def test_stop_cancels_tasks_left_on_the_loop() -> None:
    runtime = SyncRuntime()
    cancelled = threading.Event()

    async def leave_a_task() -> None:
        async def forever() -> None:
            try:
                await asyncio.sleep(30)
            except asyncio.CancelledError:
                cancelled.set()
                raise

        asyncio.get_running_loop().create_task(forever())

    runtime.run(leave_a_task())
    runtime.stop(5)
    assert cancelled.is_set()


async def test_async_callbacks_await_coroutines_and_call_plain_functions() -> None:
    seen: list[str] = []

    class Handler:
        async def on_update(self, update: ListenUpdate[Any]) -> None:
            seen.append(update.listen_id)

        def on_ended(self, reason: ListenEndedError) -> None:
            seen.append(str(reason))

    callbacks = async_callbacks(Handler())
    await callbacks.on_update(ListenUpdate("L", (), "", initial=True))
    await callbacks.on_ended(ListenEndedError("gone"))
    await callbacks.on_error(RuntimeError("no on_error is fine"))
    assert seen == ["L", "gone"]


async def test_threaded_callbacks_run_off_the_loop_thread_in_order() -> None:
    names: list[str] = []
    order: list[int] = []

    def on_update(update: ListenUpdate[Any]) -> None:
        names.append(threading.current_thread().name)
        order.append(int(update.listen_id))

    with ThreadPoolExecutor(max_workers=1, thread_name_prefix="listen") as executor:
        callbacks = threaded_callbacks(on_update, executor)
        for index in range(5):
            await callbacks.on_update(ListenUpdate(str(index), (), "", initial=False))
        await callbacks.on_error(RuntimeError("ignored without a handler"))
    assert order == [0, 1, 2, 3, 4]
    assert all(name.startswith("listen") for name in names)


def test_a_non_handler_is_refused() -> None:
    with ThreadPoolExecutor(max_workers=1) as executor, pytest.raises(LwnrdbError, match="needs a handler"):
        threaded_callbacks(object(), executor)
