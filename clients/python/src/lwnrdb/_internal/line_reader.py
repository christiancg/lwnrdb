from __future__ import annotations

from dataclasses import dataclass

_NEWLINE = b"\n"
_CARRIAGE_RETURN = b"\r"


@dataclass(frozen=True, slots=True)
class LineEvent:
    data: bytes
    too_large: bool = False


TOO_LARGE = LineEvent(b"", too_large=True)


class LineReader:
    __slots__ = ("_max_line_bytes", "_pending", "_too_large")

    def __init__(self, max_line_bytes: int) -> None:
        self._max_line_bytes = max_line_bytes
        self._pending = bytearray()
        self._too_large = False

    def push(self, chunk: bytes) -> list[LineEvent]:
        events: list[LineEvent] = []
        start = 0
        newline = chunk.find(_NEWLINE, start)
        while newline != -1:
            self._append(chunk[start:newline])
            events.append(self._take_line())
            start = newline + 1
            newline = chunk.find(_NEWLINE, start)
        self._append(chunk[start:])
        return events

    def _append(self, segment: bytes) -> None:
        if not segment or self._too_large:
            return
        if len(self._pending) + len(segment) > self._max_line_bytes:
            self._too_large = True
            self._pending = bytearray()
            return
        self._pending += segment

    def _take_line(self) -> LineEvent:
        if self._too_large:
            self._too_large = False
            return TOO_LARGE
        line = bytes(self._pending)
        self._pending = bytearray()
        if line.endswith(_CARRIAGE_RETURN):
            line = line[:-1]
        return LineEvent(line)
