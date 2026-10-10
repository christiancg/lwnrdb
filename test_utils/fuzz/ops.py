from typing import NamedTuple

from . import names, values


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
        self.databases = names.database_pool(rng)
        self.collections = names.collection_pool(rng)
        self.fields = names.field_pool(rng)
        self.ids = names.id_pool(rng)


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
