import time
from abc import ABC, abstractmethod
from typing import Optional

DURABILITIES = ("relaxed", "synced")
DATABASE = "bench"
COLLECTION = "docs"
INDEXED_FIELDS = ("category", "price")
FIELDS = ("_id", "name", "category", "price", "qty", "active", "created", "payload")


class Engine(ABC):
    name = ""
    synced = False

    def __init__(self, host: str):
        self.host = host

    def prepare(self) -> None:
        pass

    def wait_ready(self, timeout_s: float) -> None:
        deadline = time.monotonic() + timeout_s
        last_error: Optional[Exception] = None
        while time.monotonic() < deadline:
            try:
                self.connect()
                return
            except Exception as error:
                last_error = error
                time.sleep(1)
        raise RuntimeError(f"{self.name} not ready after {timeout_s:.0f}s: {last_error}")

    @abstractmethod
    def connect(self) -> None: ...

    @abstractmethod
    def close(self) -> None: ...

    @abstractmethod
    def setup(self) -> None: ...

    @abstractmethod
    def bulk_insert(self, docs: list) -> None: ...

    @abstractmethod
    def insert(self, doc: dict) -> None: ...

    @abstractmethod
    def get(self, key: str) -> Optional[dict]: ...

    @abstractmethod
    def update(self, doc: dict) -> bool: ...

    @abstractmethod
    def delete(self, key: str) -> bool: ...

    @abstractmethod
    def find_eq(self, category: str, limit: int) -> list: ...

    @abstractmethod
    def find_range(self, low: float, high: float, limit: int) -> list: ...

    @abstractmethod
    def count_range(self, low: float) -> int: ...

    @abstractmethod
    def scan_count(self, qty: int) -> int: ...

    @abstractmethod
    def count_all(self) -> int: ...
