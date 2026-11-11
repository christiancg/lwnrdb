from __future__ import annotations

import asyncio
import contextlib
import inspect
import json
from collections.abc import Awaitable, Callable
from typing import Any

from lwnrdb import ClientSettings, ServerAddress
from lwnrdb._internal.values_codec import JsonObject
from tests.support.recording_channel import ok

Responder = Callable[[JsonObject, "Session"], Awaitable[None] | None]


def ok_to_everything(request: JsonObject, session: Session) -> None:
    session.send(ok(request))


class Session:
    def __init__(self, writer: asyncio.StreamWriter) -> None:
        self.requests: list[JsonObject] = []
        self._writer = writer

    def send(self, frame: JsonObject) -> None:
        self.send_raw((json.dumps(frame) + "\n").encode())

    def send_raw(self, data: bytes) -> None:
        if not self._writer.is_closing():
            self._writer.write(data)

    def close(self) -> None:
        self._writer.close()


class FakeServer:
    def __init__(self, responder: Responder) -> None:
        self.responder = responder
        self.sessions: list[Session] = []
        self.requests: list[JsonObject] = []
        self._server: asyncio.Server | None = None
        self._arrivals = asyncio.Condition()

    @classmethod
    async def start(cls, responder: Responder = ok_to_everything) -> FakeServer:
        fake = cls(responder)
        fake._server = await asyncio.start_server(fake._accept, "127.0.0.1", 0)
        return fake

    @property
    def port(self) -> int:
        assert self._server is not None
        port: int = self._server.sockets[0].getsockname()[1]
        return port

    @property
    def hosts(self) -> tuple[ServerAddress, ...]:
        return (ServerAddress("127.0.0.1", self.port),)

    def settings(self, **overrides: Any) -> ClientSettings:
        values: dict[str, Any] = {"hosts": self.hosts, "request_timeout": 5.0, "acquire_timeout": 5.0}
        values.update(overrides)
        return ClientSettings(**values)

    def requests_of_type(self, kind: str) -> list[JsonObject]:
        return [request for request in self.requests if request.get("type") == kind]

    async def await_request(self, kind: str, count: int = 1) -> JsonObject:
        async with asyncio.timeout(5.0), self._arrivals:
            await self._arrivals.wait_for(lambda: len(self.requests_of_type(kind)) >= count)
        return self.requests_of_type(kind)[count - 1]

    async def await_session(self, index: int) -> Session:
        async with asyncio.timeout(5.0), self._arrivals:
            await self._arrivals.wait_for(lambda: len(self.sessions) > index)
        return self.sessions[index]

    async def close(self) -> None:
        for session in self.sessions:
            session.close()
        if self._server is not None:
            self._server.close()
            with contextlib.suppress(Exception):
                await asyncio.wait_for(self._server.wait_closed(), 2.0)

    async def _accept(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        session = Session(writer)
        self.sessions.append(session)
        await self._notify()
        with contextlib.suppress(ConnectionError, asyncio.IncompleteReadError, asyncio.CancelledError):
            while line := await reader.readline():
                try:
                    request = json.loads(line)
                except ValueError:
                    continue
                session.requests.append(request)
                self.requests.append(request)
                await self._notify()
                answered = self.responder(request, session)
                if inspect.isawaitable(answered):
                    await answered

    async def _notify(self) -> None:
        async with self._arrivals:
            self._arrivals.notify_all()
