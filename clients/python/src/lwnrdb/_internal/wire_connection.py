from __future__ import annotations

import asyncio
import contextlib
import json
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass

from lwnrdb._internal.frame_router import Route, route
from lwnrdb._internal.line_reader import LineEvent, LineReader
from lwnrdb._internal.response_decoder import text_of
from lwnrdb._internal.values_codec import JsonObject
from lwnrdb.errors import (
    ConnectionLostError,
    LwnrdbError,
    MappingError,
    RequestTimeoutError,
    ServerClosedConnectionError,
)
from lwnrdb.status import Status

PushSink = Callable[[JsonObject], None]
CloseListener = Callable[[LwnrdbError], None]

READ_CHUNK_BYTES = 65_536
_CLOSE_CONNECTION = "CLOSE_CONNECTION"


@dataclass(frozen=True, slots=True)
class _PendingReply:
    future: asyncio.Future[JsonObject]
    awaits_close: bool


def _refuse_constant(constant: str) -> object:
    raise ValueError(f"{constant} is not JSON")


def encode_line(frame: JsonObject) -> bytes:
    try:
        text = json.dumps(frame, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        return (text + "\n").encode("utf-8")
    except (ValueError, UnicodeEncodeError) as error:
        raise MappingError(f"the request cannot be written as JSON: {error}") from error


def decode_line(data: bytes) -> JsonObject | None:
    try:
        frame = json.loads(data.decode("utf-8"), parse_constant=_refuse_constant)
    except (ValueError, RecursionError):
        return None
    return frame if isinstance(frame, dict) else None


def _ignore_push(frame: JsonObject) -> None:
    del frame


def _ignore_close(cause: LwnrdbError) -> None:
    del cause


class WireConnection:
    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        max_reply_bytes: int,
        label: str,
    ) -> None:
        self._reader = reader
        self._writer = writer
        self._lines = LineReader(max_reply_bytes)
        self._pending: deque[_PendingReply] = deque()
        self._label = label
        self._push_sink: PushSink = _ignore_push
        self._close_listener: CloseListener = _ignore_close
        self._close_cause: LwnrdbError | None = None
        self._reader_task = asyncio.get_running_loop().create_task(self._read_loop(), name=f"lwnrdb-reader {label}")

    @property
    def is_open(self) -> bool:
        return self._close_cause is None

    @property
    def label(self) -> str:
        return self._label

    def on_push(self, sink: PushSink) -> None:
        self._push_sink = sink

    def on_close(self, listener: CloseListener) -> None:
        self._close_listener = listener

    async def request(self, frame: JsonObject, seconds: float) -> JsonObject:
        line = encode_line(frame)
        if self._close_cause is not None:
            raise self._close_cause
        future: asyncio.Future[JsonObject] = asyncio.get_running_loop().create_future()
        self._pending.append(_PendingReply(future, awaits_close=frame.get("type") == _CLOSE_CONNECTION))
        try:
            async with asyncio.timeout(seconds):
                self._writer.write(line)
                await self._writer.drain()
                return await future
        except TimeoutError:
            timed_out = RequestTimeoutError(
                f"no reply within {seconds:g} s; the connection was closed, so the request's outcome is unknown"
            )
            self.close(timed_out)
            raise timed_out from None
        except asyncio.CancelledError:
            self.close(ConnectionLostError("the request was cancelled after it was sent, so its outcome is unknown"))
            raise
        except OSError as error:
            lost = ConnectionLostError(f"could not send the request to {self._label}: {error}")
            self.close(lost)
            raise lost from error

    async def close_gracefully(self, seconds: float) -> None:
        if not self.is_open:
            return
        with contextlib.suppress(LwnrdbError):
            await self.request({"type": _CLOSE_CONNECTION}, seconds)
        self.close()

    def close(self, cause: LwnrdbError | None = None) -> None:
        if self._close_cause is not None:
            return
        self._close_cause = cause or ConnectionLostError("connection closed by the client")
        self._writer.transport.abort()
        if self._reader_task is not asyncio.current_task():
            self._reader_task.cancel()
        while self._pending:
            self._fail(self._pending.popleft().future, self._close_cause)
        self._close_listener(self._close_cause)

    async def wait_closed(self) -> None:
        with contextlib.suppress(asyncio.CancelledError):
            await self._reader_task

    async def _read_loop(self) -> None:
        try:
            while True:
                chunk = await self._reader.read(READ_CHUNK_BYTES)
                if not chunk:
                    self.close(ConnectionLostError("the server closed the connection"))
                    return
                for event in self._lines.push(chunk):
                    self._on_line(event)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            self.close(ConnectionLostError(f"lost the connection to {self._label}: {error}"))

    def _on_line(self, event: LineEvent) -> None:
        if event.too_large:
            self._fail_head(LwnrdbError("the reply exceeded max_reply_bytes and was discarded"))
            return
        if not event.data:
            return
        frame = decode_line(event.data)
        if frame is None:
            text = event.data.decode("utf-8", errors="replace")
            self._fail_head(LwnrdbError(f"the server refused the request: {text}"))
            return
        self._dispatch(frame)

    def _dispatch(self, frame: JsonObject) -> None:
        awaiting_close = bool(self._pending) and self._pending[0].awaits_close
        match route(frame, awaiting_close):
            case Route.PUSH:
                self._push_sink(frame)
            case Route.SERVER_CLOSE:
                self.close(
                    ServerClosedConnectionError(
                        f"the server closed the connection: {text_of(frame.get('message')) or ''}",
                        code=text_of(frame.get("errorCode")),
                        status=Status.ERROR,
                    )
                )
            case Route.RESPONSE:
                self._complete_head(frame)

    def _complete_head(self, frame: JsonObject) -> None:
        if not self._pending:
            self.close(ConnectionLostError(f"the server sent a reply nobody asked for: {frame}"))
            return
        future = self._pending.popleft().future
        if not future.done():
            future.set_result(frame)

    def _fail_head(self, cause: LwnrdbError) -> None:
        if self._pending:
            self._fail(self._pending.popleft().future, cause)

    @staticmethod
    def _fail(future: asyncio.Future[JsonObject], cause: LwnrdbError) -> None:
        if not future.done():
            future.set_exception(cause)
            future.add_done_callback(_consume_exception)


def _consume_exception(future: asyncio.Future[JsonObject]) -> None:
    if not future.cancelled():
        future.exception()
