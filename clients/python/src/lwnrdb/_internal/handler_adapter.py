from __future__ import annotations

import asyncio
import inspect
from collections.abc import Awaitable, Callable
from concurrent.futures import Executor
from dataclasses import dataclass
from typing import Any

from lwnrdb.errors import ListenEndedError, LwnrdbError
from lwnrdb.listen import ListenUpdate

Step = Callable[[Any], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class Callbacks:
    on_update: Callable[[ListenUpdate[Any]], Awaitable[None]]
    on_ended: Callable[[ListenEndedError], Awaitable[None]]
    on_error: Callable[[BaseException], Awaitable[None]]


async def _nothing(argument: object) -> None:
    del argument


def _method(handler: object, name: str) -> Callable[[Any], object] | None:
    if name == "on_update" and not hasattr(handler, "on_update") and callable(handler):
        return handler
    method = getattr(handler, name, None)
    return method if callable(method) else None


def _awaiting(method: Callable[[Any], object] | None) -> Step:
    if method is None:
        return _nothing

    async def call(argument: object) -> None:
        result = method(argument)
        if inspect.isawaitable(result):
            await result

    return call


def _threaded(method: Callable[[Any], object] | None, executor: Executor) -> Step:
    if method is None:
        return _nothing

    async def call(argument: object) -> None:
        await asyncio.get_running_loop().run_in_executor(executor, method, argument)

    return call


def require_handler(handler: object) -> None:
    if _method(handler, "on_update") is None:
        raise LwnrdbError("listen needs a handler: a callable, or an object with on_update")


def async_callbacks(handler: object) -> Callbacks:
    require_handler(handler)
    return Callbacks(
        _awaiting(_method(handler, "on_update")),
        _awaiting(_method(handler, "on_ended")),
        _awaiting(_method(handler, "on_error")),
    )


def threaded_callbacks(handler: object, executor: Executor) -> Callbacks:
    require_handler(handler)
    return Callbacks(
        _threaded(_method(handler, "on_update"), executor),
        _threaded(_method(handler, "on_ended"), executor),
        _threaded(_method(handler, "on_error"), executor),
    )
