from __future__ import annotations

import dataclasses
from dataclasses import dataclass

from lwnrdb._internal.listens import Listens
from lwnrdb._internal.request_channel import RequestChannel
from lwnrdb._internal.request_encoder import RequestEncoder
from lwnrdb._internal.script_request_encoder import ScriptRequestEncoder


@dataclass(frozen=True, slots=True)
class ClientContext:
    channel: RequestChannel
    encoder: RequestEncoder
    scripts: ScriptRequestEncoder
    listens: Listens | None
    username: str | None
    request_timeout: float


def with_channel(context: ClientContext, channel: RequestChannel) -> ClientContext:
    return dataclasses.replace(context, channel=channel, listens=None)
