from . import ops as op_kinds
from . import oracles
from .execute import accepted

WRITE_PATH_DB = "fzwritepaths"
SINGLE = "written_single"
BULK = "written_bulk"
TXN = "written_txn"
PATHS = (SINGLE, BULK, TXN)

CORPUS_SIZE = 12
BULK_CHUNK = 4


def _corpus(rng, namespace) -> list:
    chosen = []
    seen = set()
    for _ in range(CORPUS_SIZE):
        doc_id = rng.choice(namespace.ids)
        if doc_id in seen:
            continue
        seen.add(doc_id)
        chosen.append(op_kinds.document(rng, namespace, doc_id))
    return chosen


def _save(executor, coll: str, doc: dict) -> bool:
    return accepted(executor.request({"type": "SAVE", "databaseName": WRITE_PATH_DB,
                                      "collectionName": coll, "object": doc}))


def _write_single(executor, docs: list) -> list:
    return [doc for doc in docs if _save(executor, SINGLE, doc)]


def _write_bulk(executor, docs: list) -> None:
    for start in range(0, len(docs), BULK_CHUNK):
        executor.request({"type": "BULK_SAVE", "databaseName": WRITE_PATH_DB,
                          "collectionName": BULK,
                          "objects": docs[start:start + BULK_CHUNK]})


def _write_txn(executor, docs: list) -> None:
    conn = executor.conn()
    if not accepted(conn.send({"type": "START_TRANSACTION"})):
        return
    for doc in docs:
        _save(executor, TXN, doc)
    conn.send({"type": "COMMIT_TRANSACTION"})


def _stored_ids(executor, coll: str) -> frozenset:
    rows = oracles.rows_of(oracles.aggregate(executor, WRITE_PATH_DB, coll, []))
    if rows is None:
        return None
    return frozenset(row.get("_id") for row in rows)


def prepare(executor, rng, namespace) -> list:
    executor.request({"type": "CREATE_DATABASE", "databaseName": WRITE_PATH_DB})
    for coll in PATHS:
        executor.request({"type": "CREATE_COLLECTION", "databaseName": WRITE_PATH_DB,
                          "collectionName": coll})
    admitted = _write_single(executor, _corpus(rng, namespace))
    _write_bulk(executor, admitted)
    _write_txn(executor, admitted)
    indexed = []
    for field in namespace.fields:
        created = [accepted(executor.request({"type": "CREATE_INDEX",
                                              "databaseName": WRITE_PATH_DB,
                                              "collectionName": coll,
                                              "fieldName": field}))
                   for coll in PATHS]
        if all(created):
            indexed.append(field)
    return indexed


def membership_divergences(executor) -> list:
    stored = {coll: _stored_ids(executor, coll) for coll in PATHS}
    divergences = []
    for coll in (BULK, TXN):
        if stored[coll] != stored[SINGLE]:
            divergences.append(oracles.Divergence(
                f"write-path membership single-vs-{coll}", WRITE_PATH_DB,
                f"{SINGLE}|{coll}", "the same corpus produced a different _id set",
                stored[SINGLE], stored[coll]))
    return divergences


def compare(executor, rng, namespace, stats: oracles.Stats) -> list:
    indexed = prepare(executor, rng, namespace)
    membership = membership_divergences(executor)
    if membership:
        return membership
    divergences = []
    for query in oracles.queries_for(rng, oracles.queryable_fields(indexed)):
        stats.bump("write_path_queries")
        answers = {coll: oracles.answer_of(
            oracles.aggregate(executor, WRITE_PATH_DB, coll, query.steps), query.ordered)
            for coll in PATHS}
        for coll in (BULK, TXN):
            if answers[coll] != answers[SINGLE]:
                divergences.append(oracles.Divergence(
                    f"write-path single-vs-{coll}", WRITE_PATH_DB, f"{SINGLE}|{coll}",
                    query.label, answers[SINGLE], answers[coll]))
    return divergences
