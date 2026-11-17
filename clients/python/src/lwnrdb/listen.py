from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Generic, Protocol, TypeAlias, TypeVar

R = TypeVar("R")


@dataclass(frozen=True, slots=True)
class ListenUpdate(Generic[R]):
    listen_id: str
    results: tuple[R, ...]
    result_hash: str
    initial: bool


class ListenHandler(Protocol[R]):
    def on_update(self, update: ListenUpdate[R]) -> object: ...


Handler: TypeAlias = ListenHandler[R] | Callable[[ListenUpdate[R]], object]
