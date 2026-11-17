from __future__ import annotations

import inspect
from collections.abc import Callable
from typing import Any

from lwnrdb import Admin, AsyncAdmin, AsyncDatabase, Database
from lwnrdb._internal.portal import SyncRuntime
from lwnrdb._internal.values_codec import JsonObject
from tests.support.recording_channel import RecordingChannel, recording_context


class Recorder:
    def __init__(self, runtime: SyncRuntime | None = None) -> None:
        self.channel = RecordingChannel()
        self.context = recording_context(self.channel)
        self.encoder = self.context.encoder
        self.scripts = self.context.scripts
        self._runtime = runtime
        shop = AsyncDatabase(self.context, "shop")
        admin = AsyncAdmin(self.context)
        self.shop: Any = shop if runtime is None else Database(runtime, shop)
        self.admin: Any = admin if runtime is None else Admin(runtime, admin)
        self.orders: Any = self.shop.collection("orders")

    def raw(self, request: JsonObject) -> Any:
        sending = self.channel.send("SERVER_INFO", request)
        return sending if self._runtime is None else self._runtime.run(sending)


FixtureCall = Callable[[Recorder], object]


async def record_async(call: FixtureCall) -> Recorder:
    recorder = Recorder()
    result = call(recorder)
    if inspect.isawaitable(result):
        await result
    return recorder


def record_sync(call: FixtureCall, runtime: SyncRuntime) -> Recorder:
    recorder = Recorder(runtime)
    result = call(recorder)
    if inspect.isawaitable(result):
        raise AssertionError("a sync facade returned an awaitable")
    return recorder
