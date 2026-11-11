from __future__ import annotations

import json

from lwnrdb._internal.values_codec import Json, JsonObject, decode_document
from lwnrdb.document import Document
from lwnrdb.error_codes import error_code_details
from lwnrdb.errors import (
    AuthenticationError,
    ConflictError,
    ForbiddenError,
    ListenEndedError,
    LwnrdbError,
    NotFoundError,
    UnavailableError,
    UnknownOutcomeError,
)
from lwnrdb.status import Status

_FAILURES = frozenset({Status.ERROR, Status.NOT_FOUND, Status.UNAUTHENTICATED, Status.FORBIDDEN})
_UNKNOWN_OUTCOME = frozenset({"500-33", "500-34"})
_KNOWN_STATUSES = frozenset(status.value for status in Status)

_BY_RANGE: dict[str, type[LwnrdbError]] = {
    "401": AuthenticationError,
    "403": ForbiddenError,
    "404": NotFoundError,
    "409": ConflictError,
    "410": ListenEndedError,
    "503": UnavailableError,
}

_BY_STATUS: dict[Status, type[LwnrdbError]] = {
    Status.UNAUTHENTICATED: AuthenticationError,
    Status.FORBIDDEN: ForbiddenError,
    Status.NOT_FOUND: NotFoundError,
}


def text_of(node: Json | None) -> str | None:
    if node is None:
        return None
    if isinstance(node, dict | list):
        return json.dumps(node, ensure_ascii=False, separators=(",", ":"))
    if isinstance(node, bool):
        return "true" if node else "false"
    return str(node)


def _status_of(frame: JsonObject) -> Status | None:
    status = text_of(frame.get("status"))
    if status is None:
        return None
    return Status(status) if status in _KNOWN_STATUSES else Status.ERROR


def check(frame: JsonObject) -> JsonObject:
    status = _status_of(frame)
    if status is not None and status in _FAILURES:
        raise raise_for(frame)
    return frame


def _error_class(code: str | None, status: Status | None) -> type[LwnrdbError]:
    if code is not None and code in _UNKNOWN_OUTCOME:
        return UnknownOutcomeError
    by_range = None if code is None else _BY_RANGE.get(code[:3])
    if by_range is not None:
        return by_range
    return (None if status is None else _BY_STATUS.get(status)) or LwnrdbError


def raise_for(frame: JsonObject) -> LwnrdbError:
    code = text_of(frame.get("errorCode"))
    status = _status_of(frame)
    details = error_code_details(code)
    message = text_of(frame.get("message")) or (details.message if details is not None else None) or "request failed"
    text = message if code is None else f"{message} ({code})"
    error_class = _error_class(code, status)
    return error_class(text, code=code, status=status, operation=text_of(frame.get("type")))


def documents(frame: JsonObject, field: str) -> list[Document]:
    node = frame.get(field)
    if not isinstance(node, list):
        return []
    return [decode_document(item) for item in node if isinstance(item, dict)]


def document(frame: JsonObject, field: str) -> Document | None:
    node = frame.get(field)
    return decode_document(node) if isinstance(node, dict) else None


def strings(node: Json | None) -> tuple[str, ...]:
    if not isinstance(node, list):
        return ()
    return tuple(text_of(item) or "null" for item in node)


def boolean_of(node: Json | None, fallback: bool) -> bool:
    return node if isinstance(node, bool) else fallback


def number_of(node: Json | None) -> float | None:
    if isinstance(node, bool) or not isinstance(node, int | float):
        return None
    return node
