import json
import socket
from typing import Optional

from engines.base import COLLECTION, DATABASE, INDEXED_FIELDS, Engine

USERNAME = "admin"
PASSWORD = "Bench-Admin-7kP4wZ"
PORT = 8989


class LwnrdbError(RuntimeError):
    pass


def _filter(field: str, operator: str, value) -> dict:
    return {"fieldOperatorType": operator, "field": field, "value": value}


class LwnrdbEngine(Engine):
    name = "lwnrdb"

    def __init__(self, host: str = "lwnrdb"):
        super().__init__(host)
        self.sock: Optional[socket.socket] = None
        self.reader = None

    def connect(self) -> None:
        self.close()
        self.sock = socket.create_connection((self.host, PORT), timeout=600)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.reader = self.sock.makefile("rb")
        self._ok(self._send({"type": "AUTHENTICATE", "username": USERNAME, "password": PASSWORD}))

    def close(self) -> None:
        for closeable in (self.reader, self.sock):
            if closeable is not None:
                closeable.close()
        self.sock = None
        self.reader = None

    def _send(self, payload: dict) -> dict:
        self.sock.sendall(json.dumps(payload, separators=(",", ":")).encode() + b"\n")
        line = self.reader.readline()
        if not line:
            raise LwnrdbError("connection closed by the server")
        return json.loads(line)

    @staticmethod
    def _ok(response: dict, *accepted: str) -> dict:
        if response.get("status") not in ("OK",) + accepted:
            raise LwnrdbError(f"{response.get('status')} {response.get('errorCode')} {response.get('message')}")
        return response

    def _request(self, kind: str, **fields) -> dict:
        return self._send({"type": kind, "databaseName": DATABASE, "collectionName": COLLECTION, **fields})

    def _aggregate(self, *steps: dict) -> list:
        response = self._ok(self._request("AGGREGATE", aggregationSteps=list(steps)), "NO_RESULTS")
        return response.get("results") or []

    def _count(self, *steps: dict) -> int:
        rows = self._aggregate(*steps, {"type": "COUNT"})
        return int(rows[0]["count"]) if rows else 0

    def setup(self) -> None:
        self._ok(self._send({"type": "CREATE_DATABASE", "databaseName": DATABASE}))
        self._ok(self._request("CREATE_COLLECTION"))
        for field in INDEXED_FIELDS:
            self._ok(self._request("CREATE_INDEX", fieldName=field))

    def bulk_insert(self, docs: list) -> None:
        self._ok(self._request("BULK_SAVE", objects=docs))

    def insert(self, doc: dict) -> None:
        self._ok(self._request("SAVE", object=doc))

    def get(self, key: str) -> Optional[dict]:
        response = self._request("FIND_BY_ID", _id=key)
        return response.get("object") if response.get("status") == "OK" else None

    def update(self, doc: dict) -> bool:
        self._ok(self._request("SAVE", object=doc))
        return True

    def delete(self, key: str) -> bool:
        return self._request("DELETE", _id=key).get("status") == "OK"

    def find_eq(self, category: str, limit: int) -> list:
        return self._aggregate({"type": "FILTER", "operator": _filter("category", "EQUALS", category)},
                               {"type": "LIMIT", "limit": limit})

    def find_range(self, low: float, high: float, limit: int) -> list:
        between = {"conjunctionType": "AND", "operators": [_filter("price", "GREATER_THAN_EQUALS", low),
                                                           _filter("price", "SMALLER_THAN", high)]}
        return self._aggregate({"type": "FILTER", "operator": between},
                               {"type": "SORT", "fieldName": "price", "ascending": True},
                               {"type": "LIMIT", "limit": limit})

    def count_range(self, low: float) -> int:
        return self._count({"type": "FILTER", "operator": _filter("price", "GREATER_THAN_EQUALS", low)})

    def scan_count(self, qty: int) -> int:
        return self._count({"type": "FILTER", "operator": _filter("qty", "EQUALS", qty)})

    def count_all(self) -> int:
        return self._count()
