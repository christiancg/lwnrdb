import random
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

import base_utils as bu
from fuzz import values

OUTCOME_OK = "ok"
OUTCOME_ERROR = "error"
OUTCOME_UNKNOWN = "unknown"

SEVERITY_FAIL = "fail"
SEVERITY_OBSERVE = "observe"

ACCEPTED = ("OK", "CREATED", "UPDATED", "DELETED")
CLOSED_MESSAGE = "Server closed connection unexpectedly"

SAVE = "SAVE"
DELETE = "DELETE"
DELETE_WHERE = "DELETE_WHERE"
BULK_SAVE = "BULK_SAVE"
TRANSACTION = "TRANSACTION"
CREATE_INDEX = "CREATE_INDEX"
DROP_INDEX = "DROP_INDEX"
REINDEX = "REINDEX"
ID_OPS = (SAVE, DELETE, BULK_SAVE, TRANSACTION, DELETE_WHERE)

INDEXED_FIELDS = ["v", "n"]
CHURNED_INDEX = "tag"
TAGS = ["red", "green", "blue", "Red", ""]
MAX_PAD = 1200
MAX_BULK = 40
DELETE_WHERE_SHARE = 0.3
MAX_DELETE_WHERE_EXTRA = 4
SETUP_BULK = 50


@dataclass
class HistoryEntry:
    op: str
    coll: str
    ids: list
    values: list
    sent_at: float
    outcome: str
    error_code: str = ""
    answered_at: float = 0.0
    client: int = -1
    present: list = field(default_factory=list)


@dataclass
class History:
    seed: int
    entries: list = field(default_factory=list)

    def by_id(self) -> dict:
        timeline = {}
        for entry in sorted(self.entries, key=lambda e: e.sent_at):
            if entry.op not in ID_OPS:
                continue
            for doc_id, token in zip(entry.ids, entry.values):
                timeline.setdefault((entry.coll, doc_id), []).append((entry, token))
        return timeline


@dataclass
class WorkloadSchema:
    db: str = "durability_db"
    collections: list = field(default_factory=list)
    indexed_fields: dict = field(default_factory=dict)
    work_dir: str = ""
    ids: dict = field(default_factory=dict)


@dataclass
class Divergence:
    oracle: str
    seed: int
    detail: str
    severity: str = SEVERITY_FAIL


Connect = Callable[[], bu.Conn]


def outcome_of(response: dict) -> tuple:
    status = response.get("status")
    if status in ACCEPTED:
        return OUTCOME_OK, ""
    if status == "ERROR" and response.get("message") == CLOSED_MESSAGE and not response.get("errorCode"):
        return OUTCOME_UNKNOWN, ""
    return OUTCOME_ERROR, str(response.get("errorCode") or status)


class Workload:
    def __init__(self, seed: int, clients: int = 4, collections: int = 3, docs: int = 400):
        self.seed = seed
        self.clients = clients
        self.collections = collections
        self.docs = docs
        self.rng = random.Random(seed)
        self.schema: Optional[WorkloadSchema] = None
        self._entries = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._threads = []
        self._tokens = 0

    def setup(self, connect: Connect) -> WorkloadSchema:
        names = [f"pl{i}" for i in range(self.collections)]
        schema = WorkloadSchema(collections=names, indexed_fields={name: list(INDEXED_FIELDS) for name in names})
        schema.ids = {name: [f"d{i}" for i in range(self.docs)] for name in names}
        with connect() as conn:
            conn.send({"type": "CREATE_DATABASE", "databaseName": schema.db})
            for name in names:
                conn.send({"type": "CREATE_COLLECTION", "databaseName": schema.db, "collectionName": name})
                for index_field in INDEXED_FIELDS:
                    conn.send({"type": "CREATE_INDEX", "databaseName": schema.db, "collectionName": name,
                               "fieldName": index_field})
            for name in names:
                seeded = schema.ids[name][:self.docs // 2]
                for start in range(0, len(seeded), SETUP_BULK):
                    self._bulk(conn, schema, self.rng, name, seeded[start:start + SETUP_BULK], -1)
        self.schema = schema
        return schema

    def start(self, connect: Connect) -> None:
        self._stop.clear()
        for client in range(self.clients):
            thread = threading.Thread(target=self._client_loop, args=(connect, client), daemon=True)
            self._threads.append(thread)
            thread.start()

    def stop(self, timeout_s: float = 30.0) -> History:
        self._stop.set()
        deadline = time.time() + timeout_s
        for thread in self._threads:
            thread.join(timeout=max(0.0, deadline - time.time()))
        self._threads = []
        return self.history()

    def history(self) -> History:
        with self._lock:
            return History(self.seed, sorted(self._entries, key=lambda e: e.sent_at))

    def run_sequence(self, conn: bu.Conn, ops: list) -> History:
        schema = self._require_schema()
        rng = random.Random(self.seed ^ 0x5EED)
        for op in ops:
            kind = op["op"]
            coll = op.get("coll", schema.collections[0])
            if kind == SAVE:
                self._save(conn, schema, rng, coll, op["ids"][0], -1)
            elif kind == DELETE:
                self._delete(conn, schema, coll, op["ids"][0], -1)
            elif kind == DELETE_WHERE:
                self._delete_where(conn, schema, coll, op["ids"], -1)
            elif kind == BULK_SAVE:
                self._bulk(conn, schema, rng, coll, op["ids"], -1)
            elif kind == TRANSACTION:
                self._transaction(conn, schema, rng, coll, op["steps"], -1)
            else:
                self._admin(conn, schema, coll, kind, op.get("field", CHURNED_INDEX), -1, op.get("primaryKey", False))
        return self.history()

    def fixed_sequence(self, coll: Optional[str] = None) -> list:
        schema = self._require_schema()
        coll = coll or schema.collections[0]
        ids = schema.ids[coll]
        half = self.docs // 2
        return [
            {"op": SAVE, "coll": coll, "ids": [ids[0]]},
            {"op": SAVE, "coll": coll, "ids": [ids[half]]},
            {"op": DELETE, "coll": coll, "ids": [ids[1]]},
            {"op": BULK_SAVE, "coll": coll, "ids": ids[2:2 + MAX_BULK]},
            {"op": TRANSACTION, "coll": coll,
             "steps": [(SAVE, ids[half + 1]), (SAVE, ids[half + 2]), (DELETE, ids[half + 3])]},
            {"op": CREATE_INDEX, "coll": coll, "field": CHURNED_INDEX},
        ]

    def _require_schema(self) -> WorkloadSchema:
        if self.schema is None:
            raise RuntimeError("call setup() before running operations")
        return self.schema

    def _owned(self, schema: WorkloadSchema, coll: str, client: int) -> list:
        return [doc_id for i, doc_id in enumerate(schema.ids[coll]) if i % self.clients == client]

    def _client_loop(self, connect: Connect, client: int) -> None:
        schema = self._require_schema()
        rng = random.Random(self.seed * 1009 + client)
        try:
            conn = connect()
        except OSError:
            return
        with conn:
            while not self._stop.is_set():
                coll = rng.choice(schema.collections)
                owned = self._owned(schema, coll, client)
                if not self._one_op(conn, schema, rng, coll, owned, client):
                    return

    def _one_op(self, conn, schema, rng, coll, owned, client) -> bool:
        roll = rng.random()
        if roll < 0.5:
            entry = self._save(conn, schema, rng, coll, rng.choice(owned), client)
        elif roll < 0.62:
            doc_id = rng.choice(owned)
            side = random.Random(hash(rng.getstate()[1]))
            if side.random() < DELETE_WHERE_SHARE and len(owned) > 1:
                others = [other for other in owned if other != doc_id]
                extra = side.sample(others, side.randint(1, min(MAX_DELETE_WHERE_EXTRA, len(others))))
                entry = self._delete_where(conn, schema, coll, [doc_id] + extra, client)
            else:
                entry = self._delete(conn, schema, coll, doc_id, client)
        elif roll < 0.76:
            entry = self._bulk(conn, schema, rng, coll, rng.sample(owned, rng.randint(2, min(MAX_BULK, len(owned)))),
                               client)
        elif roll < 0.92 or client != 0:
            picked = rng.sample(owned, rng.randint(2, min(6, len(owned))))
            steps = [(DELETE if rng.random() < 0.25 else SAVE, doc_id) for doc_id in picked]
            entry = self._transaction(conn, schema, rng, coll, steps, client)
        elif roll < 0.98:
            kind = rng.choice([CREATE_INDEX, DROP_INDEX])
            entry = self._admin(conn, schema, coll, kind, CHURNED_INDEX, client, False)
        else:
            entry = self._admin(conn, schema, coll, REINDEX, None, client, rng.random() < 0.5)
        return entry.outcome != OUTCOME_UNKNOWN

    def _token(self, client: int) -> str:
        with self._lock:
            self._tokens += 1
            return f"s{self.seed}c{client}w{self._tokens}"

    def _document(self, rng, doc_id: str, token: str) -> dict:
        doc = {"_id": doc_id, "w": token, "v": values.operand(rng), "n": rng.choice(values.INTERESTING_NUMBERS),
               "pad": "p" * rng.randint(0, MAX_PAD)}
        if rng.random() < 0.6:
            doc[CHURNED_INDEX] = rng.choice(TAGS)
        return doc

    def _record(self, op, coll, ids, tokens, client) -> HistoryEntry:
        entry = HistoryEntry(op=op, coll=coll, ids=list(ids), values=list(tokens), sent_at=time.time(),
                             outcome=OUTCOME_UNKNOWN, client=client)
        with self._lock:
            self._entries.append(entry)
        return entry

    @staticmethod
    def _answer(entry: HistoryEntry, response: dict) -> HistoryEntry:
        entry.outcome, entry.error_code = outcome_of(response)
        entry.answered_at = time.time()
        return entry

    def _save(self, conn, schema, rng, coll, doc_id, client) -> HistoryEntry:
        token = self._token(client)
        entry = self._record(SAVE, coll, [doc_id], [token], client)
        return self._answer(entry, conn.send({"type": "SAVE", "databaseName": schema.db, "collectionName": coll,
                                              "object": self._document(rng, doc_id, token)}))

    def _delete(self, conn, schema, coll, doc_id, client) -> HistoryEntry:
        entry = self._record(DELETE, coll, [doc_id], [None], client)
        return self._answer(entry, conn.send({"type": "DELETE", "databaseName": schema.db, "collectionName": coll,
                                              "_id": doc_id}))

    def _delete_where(self, conn, schema, coll, ids, client) -> HistoryEntry:
        present = self._last_written(coll, ids)
        entry = self._record(DELETE_WHERE, coll, ids, [None] * len(ids), client)
        entry.present = present
        return self._answer(entry, conn.send({"type": "DELETE", "databaseName": schema.db, "collectionName": coll,
                                              "condition": {"fieldOperatorType": "IN", "field": "_id",
                                                            "value": list(ids)}}))

    def _last_written(self, coll, ids) -> list:
        last = {}
        with self._lock:
            for entry in sorted(self._entries, key=lambda e: e.sent_at):
                for doc_id, token in zip(entry.ids, entry.values, strict=True):
                    if entry.coll == coll and doc_id in ids:
                        last[doc_id] = (entry.outcome, token)
        return [doc_id for doc_id in ids
                if doc_id in last and last[doc_id][0] == OUTCOME_OK and last[doc_id][1] is not None]

    def _bulk(self, conn, schema, rng, coll, ids, client) -> HistoryEntry:
        tokens = [self._token(client) for _ in ids]
        entry = self._record(BULK_SAVE, coll, ids, tokens, client)
        docs = [self._document(rng, doc_id, token) for doc_id, token in zip(ids, tokens)]
        return self._answer(entry, conn.send({"type": "BULK_SAVE", "databaseName": schema.db, "collectionName": coll,
                                              "objects": docs}))

    def _transaction(self, conn, schema, rng, coll, steps, client) -> HistoryEntry:
        effects = {}
        for kind, doc_id in steps:
            effects[doc_id] = self._token(client) if kind == SAVE else None
        entry = self._record(TRANSACTION, coll, list(effects), list(effects.values()), client)
        started = conn.send({"type": "START_TRANSACTION"})
        if outcome_of(started)[0] != OUTCOME_OK:
            return self._answer(entry, started)
        for kind, doc_id in steps:
            if kind == SAVE:
                token = effects[doc_id]
                response = conn.send({"type": "SAVE", "databaseName": schema.db, "collectionName": coll,
                                      "object": self._document(rng, doc_id, token)})
            else:
                response = conn.send({"type": "DELETE", "databaseName": schema.db, "collectionName": coll,
                                      "_id": doc_id})
            outcome, _ = outcome_of(response)
            if outcome == OUTCOME_UNKNOWN:
                return self._answer(entry, response)
            if outcome == OUTCOME_ERROR and not (kind == DELETE and response.get("status") == "NOT_FOUND"):
                rolled = conn.send({"type": "ROLLBACK_TRANSACTION"})
                return self._answer(entry, rolled if outcome_of(rolled)[0] == OUTCOME_UNKNOWN else response)
        return self._answer(entry, conn.send({"type": "COMMIT_TRANSACTION"}))

    def _admin(self, conn, schema, coll, kind, index_field, client, primary_key) -> HistoryEntry:
        entry = self._record(kind, coll, [], [], client)
        request = {"type": kind, "databaseName": schema.db, "collectionName": coll}
        if kind == REINDEX:
            request["primaryKey"] = primary_key
        else:
            request["fieldName"] = index_field
        return self._answer(entry, conn.send(request))
