from __future__ import annotations

import copy
from collections.abc import Callable
from typing import Any

from lwnrdb._internal.client_context import ClientContext
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.response_decoder import check
from lwnrdb._internal.script_request_encoder import ScriptRequestEncoder
from lwnrdb._internal.values_codec import EncodeOptions, JsonObject
from lwnrdb.settings import check_zone

Replier = Callable[[JsonObject], JsonObject]


def ok(request: JsonObject, **extra: Any) -> JsonObject:
    return {"type": request.get("type"), "status": "OK", "message": "Ok", **extra}


def error(request: JsonObject, status: str, code: str, message: str = "refused") -> JsonObject:
    return {"type": request.get("type"), "status": status, "message": message, "errorCode": code}


class RecordingChannel:
    def __init__(self) -> None:
        self.requests: list[JsonObject] = []
        self.operations: list[str] = []
        self.replies: Replier = ok

    async def send(self, operation: str, request: JsonObject) -> JsonObject:
        self.requests.append(copy.deepcopy(request))
        self.operations.append(operation)
        return check(self.replies(request))

    def last(self) -> JsonObject:
        if not self.requests:
            raise AssertionError("no request was sent")
        return self.requests[-1]


def recording_context(channel: RecordingChannel, temporal_zone: str | None = None) -> ClientContext:
    zone = None if temporal_zone is None else check_zone(temporal_zone)
    encoder = RequestEncoder(EncodeOptions(zone))
    return ClientContext(
        channel=channel,
        encoder=encoder,
        scripts=ScriptRequestEncoder(encoder),
        listens=None,
        username="alice",
        request_timeout=5.0,
    )
