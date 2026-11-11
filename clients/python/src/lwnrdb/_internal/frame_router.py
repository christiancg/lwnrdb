from __future__ import annotations

from enum import Enum

from lwnrdb._internal.values_codec import JsonObject

LISTEN_ENDED_CODE = "410-1"


class Route(Enum):
    RESPONSE = "RESPONSE"
    PUSH = "PUSH"
    SERVER_CLOSE = "SERVER_CLOSE"


def is_listen_end(frame: JsonObject) -> bool:
    return frame.get("errorCode") == LISTEN_ENDED_CODE


def _is_push(frame: JsonObject) -> bool:
    return frame.get("isUpdate") is True or is_listen_end(frame)


def route(frame: JsonObject, awaiting_close: bool) -> Route:
    kind = frame.get("type")
    if kind == "LISTEN" and _is_push(frame):
        return Route.PUSH
    if kind == "CLOSE_CONNECTION" and not awaiting_close:
        return Route.SERVER_CLOSE
    return Route.RESPONSE
