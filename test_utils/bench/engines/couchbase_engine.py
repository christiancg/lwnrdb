import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import timedelta
from typing import Callable, Optional

from couchbase.auth import PasswordAuthenticator
from couchbase.cluster import Cluster
from couchbase.durability import DurabilityLevel, ServerDurability
from couchbase.exceptions import CouchbaseException, DocumentNotFoundException
from couchbase.n1ql import QueryScanConsistency
from couchbase.options import (ClusterOptions, InsertMultiOptions, InsertOptions, QueryOptions, RemoveOptions,
                               ReplaceOptions)

from engines.base import INDEXED_FIELDS, Engine

USERNAME = "Administrator"
PASSWORD = "bench-pass"
BUCKET = "bench"
KEYSPACE = f"`{BUCKET}`.`_default`.`_default`"
DOCUMENT = "META(d).id AS _id, d.name, d.category, d.price, d.qty, d.active, d.created, d.payload"


def _body(doc: dict) -> dict:
    return {key: value for key, value in doc.items() if key != "_id"}


class CouchbaseEngine(Engine):
    name = "couchbase"

    def __init__(self, host: str = "couchbase.bench.local"):
        super().__init__(host)
        self.cluster = None
        self.collection = None
        self.query_options = None
        self.write_options = None

    def _rest(self, method: str, path: str, form: Optional[dict] = None, auth: bool = True):
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        request = urllib.request.Request(f"http://{self.host}:8091{path}", data=data, method=method)
        if auth:
            token = base64.b64encode(f"{USERNAME}:{PASSWORD}".encode()).decode()
            request.add_header("Authorization", f"Basic {token}")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
        except urllib.error.HTTPError as error:
            if error.code >= 500 or method == "GET":
                raise
            raise RuntimeError(f"couchbase {method} {path}: {error.code} {error.read().decode()}") from error
        return json.loads(body) if body.strip().startswith((b"{", b"[")) else body.decode()

    @staticmethod
    def _await(label: str, probe: Callable[[], object], timeout_s: float = 300) -> None:
        deadline = time.monotonic() + timeout_s
        last_error = None
        while time.monotonic() < deadline:
            try:
                if probe():
                    return
            except (urllib.error.URLError, OSError, ValueError) as error:
                last_error = error
            time.sleep(1)
        raise RuntimeError(f"couchbase: {label} not reached in {timeout_s:.0f}s ({last_error})")

    def _initialized(self) -> bool:
        try:
            self._rest("GET", "/pools/default")
            return True
        except urllib.error.HTTPError:
            return False

    def _bucket_healthy(self) -> bool:
        nodes = self._rest("GET", f"/pools/default/buckets/{BUCKET}").get("nodes", [])
        return bool(nodes) and all(node.get("status") == "healthy" for node in nodes)

    def prepare(self) -> None:
        self._await("REST endpoint", lambda: self._rest("GET", "/pools", auth=False) is not None)
        if not self._initialized():
            self._rest("POST", "/clusterInit", {
                "hostname": self.host, "services": "kv,index,n1ql",
                "memoryQuota": os.environ.get("COUCHBASE_DATA_QUOTA", "2048"),
                "indexMemoryQuota": os.environ.get("COUCHBASE_INDEX_QUOTA", "614"),
                "indexerStorageMode": "forestdb", "username": USERNAME, "password": PASSWORD, "port": "SAME",
            }, auth=False)
            self._rest("POST", "/pools/default/buckets", {
                "name": BUCKET, "bucketType": "couchbase", "replicaNumber": "0",
                "ramQuota": os.environ.get("COUCHBASE_DATA_QUOTA", "2048"), "flushEnabled": "1",
            })
        self._await("healthy bucket", self._bucket_healthy)

    def connect(self) -> None:
        self.close()
        self.cluster = Cluster(f"couchbase://{self.host}?enable_dns_srv=false",
                               ClusterOptions(PasswordAuthenticator(USERNAME, PASSWORD)))
        self.cluster.wait_until_ready(timedelta(seconds=30))
        self.collection = self.cluster.bucket(BUCKET).default_collection()
        self.query_options = lambda *params: QueryOptions(positional_parameters=list(params), adhoc=False,
                                                          scan_consistency=QueryScanConsistency.REQUEST_PLUS,
                                                          timeout=timedelta(minutes=10))
        durability = ServerDurability(DurabilityLevel.PERSIST_TO_MAJORITY) if self.synced else None
        self.write_options = lambda options_type: options_type(durability=durability) if durability else options_type()

    def close(self) -> None:
        if self.cluster is not None:
            self.cluster.close()
        self.cluster = None
        self.collection = None

    def _query(self, statement: str, *params) -> list:
        return list(self.cluster.query(statement, self.query_options(*params)).rows())

    def _ddl(self, statement: str, timeout_s: float = 300) -> None:
        deadline = time.monotonic() + timeout_s
        while True:
            try:
                self.cluster.query(statement).execute()
                return
            except CouchbaseException:
                if time.monotonic() > deadline:
                    raise
                time.sleep(2)

    def setup(self) -> None:
        self._ddl(f"CREATE PRIMARY INDEX IF NOT EXISTS ON {KEYSPACE}")
        for field in INDEXED_FIELDS:
            self._ddl(f"CREATE INDEX IF NOT EXISTS idx_{field} ON {KEYSPACE}({field})")

    def bulk_insert(self, docs: list) -> None:
        result = self.collection.insert_multi({doc["_id"]: _body(doc) for doc in docs},
                                              self.write_options(InsertMultiOptions))
        if not result.all_ok:
            raise RuntimeError(f"insert_multi failed: {next(iter(result.exceptions.values()))}")

    def insert(self, doc: dict) -> None:
        self.collection.insert(doc["_id"], _body(doc), self.write_options(InsertOptions))

    def get(self, key: str) -> Optional[dict]:
        try:
            return {"_id": key, **self.collection.get(key).content_as[dict]}
        except DocumentNotFoundException:
            return None

    def update(self, doc: dict) -> bool:
        try:
            self.collection.replace(doc["_id"], _body(doc), self.write_options(ReplaceOptions))
            return True
        except DocumentNotFoundException:
            return False

    def delete(self, key: str) -> bool:
        try:
            self.collection.remove(key, self.write_options(RemoveOptions))
            return True
        except DocumentNotFoundException:
            return False

    def find_eq(self, category: str, limit: int) -> list:
        return self._query(f"SELECT {DOCUMENT} FROM {KEYSPACE} d WHERE d.category = $1 LIMIT $2", category, limit)

    def find_range(self, low: float, high: float, limit: int) -> list:
        return self._query(f"SELECT {DOCUMENT} FROM {KEYSPACE} d WHERE d.price >= $1 AND d.price < $2 "
                           f"ORDER BY d.price LIMIT $3", low, high, limit)

    def count_range(self, low: float) -> int:
        return int(self._query(f"SELECT RAW COUNT(*) FROM {KEYSPACE} d WHERE d.price >= $1", low)[0])

    def scan_count(self, qty: int) -> int:
        return int(self._query(f"SELECT RAW COUNT(*) FROM {KEYSPACE} d WHERE d.qty = $1", qty)[0])

    def count_all(self) -> int:
        return int(self._query(f"SELECT RAW COUNT(*) FROM {KEYSPACE} d")[0])
