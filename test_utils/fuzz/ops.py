import string
from typing import NamedTuple

from . import values


NAME_ALPHABET = string.ascii_letters + string.digits + "_-"
FIELD_ALPHABET = string.ascii_letters + string.digits + "_.-"

COLLISION_PAIRS = [
    ("MyColl", "mycoll"),
    ("Docs", "docs"),
]

RESERVED_NEAR_MISSES = [
    "Admin",
    "ADMIN",
    "Script_Runs",
    "SCRIPT_RUNS",
    "admin_Pages",
]

FIELD_COLLISION_PAIRS = [
    ("first", "first-name"),
    ("Foo", "foo"),
    ("a", "a.b"),
]

RESERVED_FIELD_NAMES = ["_id", "tombstones"]


def plain_name(rng, minimum: int = 3, maximum: int = 12) -> str:
    size = rng.randint(minimum, maximum)
    return "".join(rng.choice(NAME_ALPHABET) for _ in range(size))


def plain_field(rng) -> str:
    size = rng.randint(1, 10)
    return "".join(rng.choice(FIELD_ALPHABET) for _ in range(size))


def database_pool(rng, count: int = 2) -> list:
    pool = [f"fz{plain_name(rng, 3, 6)}" for _ in range(count)]
    if rng.random() < 0.25:
        pool.append(rng.choice(RESERVED_NEAR_MISSES))
    return pool


def collection_pool(rng, count: int = 3) -> list:
    pool = [f"c{plain_name(rng, 3, 6)}" for _ in range(count)]
    if rng.random() < 0.35:
        pool.extend(rng.choice(COLLISION_PAIRS))
    if rng.random() < 0.2:
        pool.append(rng.choice(RESERVED_NEAR_MISSES))
    return pool


def field_pool(rng, count: int = 4) -> list:
    pool = [f"f{plain_field(rng)}" for _ in range(count)]
    if rng.random() < 0.4:
        pool.extend(rng.choice(FIELD_COLLISION_PAIRS))
    if rng.random() < 0.15:
        pool.append(rng.choice(RESERVED_FIELD_NAMES))
    return [name for name in pool if len(name) <= 64]


def id_pool(rng, count: int = 8) -> list:
    pool = []
    for _ in range(count):
        base = plain_name(rng, 1, 8)
        pool.append(base)
        if rng.random() < 0.3:
            flipped = base.swapcase()
            if flipped != base:
                pool.append(flipped)
    return pool


class Op(NamedTuple):
    kind: str
    db: str = None
    coll: str = None
    field: str = None
    docs: list = None
    ids: list = None
    nested: list = None


CREATE_DATABASE = "CREATE_DATABASE"
CREATE_COLLECTION = "CREATE_COLLECTION"
SAVE = "SAVE"
BULK_SAVE = "BULK_SAVE"
DELETE = "DELETE"
CREATE_INDEX = "CREATE_INDEX"
DROP_INDEX = "DROP_INDEX"
REINDEX = "REINDEX"
DROP_COLLECTION = "DROP_COLLECTION"
DROP_DATABASE = "DROP_DATABASE"
TRANSACTION = "TRANSACTION"
RESTART = "RESTART"

NEEDS_DATABASE = (CREATE_COLLECTION, DROP_DATABASE)
NEEDS_COLLECTION = (SAVE, BULK_SAVE, DELETE, CREATE_INDEX, DROP_INDEX, REINDEX,
                    DROP_COLLECTION)


class Namespace:
    def __init__(self, rng):
        self.databases = database_pool(rng)
        self.collections = collection_pool(rng)
        self.fields = field_pool(rng)
        self.ids = id_pool(rng)


class PlanState:
    def __init__(self):
        self.databases = set()
        self.collections = set()

    def apply(self, op: Op) -> None:
        if op.kind == CREATE_DATABASE:
            self.databases.add(op.db)
        elif op.kind == CREATE_COLLECTION:
            self.collections.add((op.db, op.coll))
        elif op.kind == DROP_COLLECTION:
            self.collections.discard((op.db, op.coll))
        elif op.kind == DROP_DATABASE:
            self.databases.discard(op.db)
            self.collections = {c for c in self.collections if c[0] != op.db}

    def permits(self, op: Op) -> bool:
        if op.kind in NEEDS_DATABASE:
            return op.db in self.databases
        if op.kind in NEEDS_COLLECTION:
            return (op.db, op.coll) in self.collections
        if op.kind == TRANSACTION:
            return bool(op.nested)
        return True

    def live_collections(self) -> list:
        return sorted(self.collections)


def document(rng, namespace: Namespace, doc_id: str) -> dict:
    doc = {"_id": doc_id}
    for field in namespace.fields:
        if field == "_id":
            continue
        if rng.random() < 0.6:
            doc[field] = values.value(rng)
    return doc


def _write_op(rng, namespace: Namespace, db: str, coll: str) -> Op:
    roll = rng.random()
    if roll < 0.55:
        doc_id = rng.choice(namespace.ids)
        return Op(kind=SAVE, db=db, coll=coll, docs=[document(rng, namespace, doc_id)])
    if roll < 0.85:
        count = rng.randint(1, 4)
        chosen = [rng.choice(namespace.ids) for _ in range(count)]
        docs = [document(rng, namespace, doc_id) for doc_id in chosen]
        return Op(kind=BULK_SAVE, db=db, coll=coll, docs=docs)
    return Op(kind=DELETE, db=db, coll=coll, ids=[rng.choice(namespace.ids)])


def _choose_op(rng, namespace: Namespace, state: PlanState) -> Op:
    live = state.live_collections()
    if not state.databases or rng.random() < 0.08:
        return Op(kind=CREATE_DATABASE, db=rng.choice(namespace.databases))
    if not live or rng.random() < 0.12:
        return Op(kind=CREATE_COLLECTION, db=rng.choice(sorted(state.databases)),
                  coll=rng.choice(namespace.collections))
    db, coll = rng.choice(live)
    roll = rng.random()
    if roll < 0.62:
        return _write_op(rng, namespace, db, coll)
    if roll < 0.72:
        return Op(kind=CREATE_INDEX, db=db, coll=coll, field=rng.choice(namespace.fields))
    if roll < 0.75:
        return Op(kind=DROP_INDEX, db=db, coll=coll, field=rng.choice(namespace.fields))
    if roll < 0.78:
        return Op(kind=REINDEX, db=db, coll=coll)
    if roll < 0.90:
        nested = [_write_op(rng, namespace, db, coll) for _ in range(rng.randint(1, 3))]
        return Op(kind=TRANSACTION, db=db, coll=coll, nested=nested)
    if roll < 0.94:
        return Op(kind=RESTART)
    if roll < 0.98:
        return Op(kind=DROP_COLLECTION, db=db, coll=coll)
    return Op(kind=DROP_DATABASE, db=db)


def generate_plan(rng, length: int):
    namespace = Namespace(rng)
    state = PlanState()
    plan = []
    for _ in range(length):
        op = _choose_op(rng, namespace, state)
        state.apply(op)
        plan.append(op)
    return plan, namespace


def repair(plan: list) -> list:
    state = PlanState()
    kept = []
    for op in plan:
        candidate = op
        if op.kind == TRANSACTION:
            nested = [n for n in (op.nested or []) if state.permits(n)]
            if not nested:
                continue
            candidate = Op(kind=op.kind, db=op.db, coll=op.coll, nested=nested)
        if not state.permits(candidate):
            continue
        state.apply(candidate)
        kept.append(candidate)
    return kept


def plan_literal(plan: list) -> str:
    body = ",\n    ".join(repr(op) for op in plan)
    return "PLAN = [\n    " + body + ",\n]\n"
