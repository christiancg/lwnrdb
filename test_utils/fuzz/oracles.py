import json
from collections import Counter, namedtuple

from . import values

SCALAR_OPERATORS = [
    "EQUALS",
    "NOT_EQUALS",
    "GREATER_THAN",
    "GREATER_THAN_EQUALS",
    "SMALLER_THAN",
    "SMALLER_THAN_EQUALS",
    "CONTAINS",
]

LIST_OPERATORS = ["IN", "NOT_IN"]

EMPTY_RESULT_CODE = "404-3"

PRIMARY_KEY_FIELD = "_id"

FILTER_QUERY = "FILTER"
SORT_QUERY = "SORT"
RESHAPING_QUERY = "RESHAPING"

Query = namedtuple("Query", "label steps ordered kind")


class Stats:
    def __init__(self):
        self.counters = {}

    def bump(self, key: str, amount: int = 1) -> None:
        self.counters[key] = self.counters.get(key, 0) + amount

    def get(self, key: str) -> int:
        return self.counters.get(key, 0)

    def render(self) -> str:
        return ", ".join(f"{k}={v}" for k, v in sorted(self.counters.items()))


class Divergence:
    def __init__(self, oracle: str, db: str, coll: str, label: str, left, right):
        self.oracle = oracle
        self.db = db
        self.coll = coll
        self.label = label
        self.left = left
        self.right = right

    def describe(self) -> str:
        return (f"{self.oracle} on {self.db}|{self.coll} :: {self.label}\n"
                f"           left={_render(self.left)}\n"
                f"           right={_render(self.right)}")


def _render(answer) -> str:
    if isinstance(answer, frozenset):
        return repr(sorted(answer))
    if isinstance(answer, tuple) and answer and answer[0] == "ERROR":
        return repr(answer)
    if isinstance(answer, tuple):
        return repr(list(answer))
    return repr(answer)


def rows_of(response: dict):
    status = response.get("status")
    if status == "OK":
        return response.get("results") or []
    if status == "NOT_FOUND" and response.get("errorCode") == EMPTY_RESULT_CODE:
        return []
    return None


def answer_of(response: dict, ordered: bool = False):
    rows = rows_of(response)
    if rows is None:
        return "ERROR", response.get("errorCode") or response.get("status")
    keys = [json.dumps(row, sort_keys=True) for row in rows]
    if ordered:
        return tuple(keys)
    return frozenset(Counter(keys).items())


def count_of(response: dict):
    rows = rows_of(response)
    if rows is None:
        return "ERROR", response.get("errorCode") or response.get("status")
    if not rows:
        return 0
    return rows[0].get("count")


def forced_scan(steps: list) -> list:
    return [{"type": "SKIP", "skip": 0}] + steps


def filter_step(field: str, operator: str, operand) -> dict:
    return {"type": "FILTER",
            "operator": {"fieldOperatorType": operator, "field": field, "value": operand}}


def aggregate(executor, db: str, coll: str, steps: list) -> dict:
    return executor.request({"type": "AGGREGATE", "databaseName": db,
                             "collectionName": coll, "aggregationSteps": steps})


def queries_for(rng, fields: list, operands_per_operator: int = 2) -> list:
    queries = []
    for field in fields:
        for operator in SCALAR_OPERATORS:
            for _ in range(operands_per_operator):
                operand = values.operand(rng)
                label = f"FILTER {field} {operator} {values.to_json_text(operand)}"
                queries.append(Query(label, [filter_step(field, operator, operand)],
                                     False, FILTER_QUERY))
        for operator in LIST_OPERATORS:
            operand = values.operand_list(rng)
            label = f"FILTER {field} {operator} {values.to_json_text(operand)}"
            queries.append(Query(label, [filter_step(field, operator, operand)],
                                 False, FILTER_QUERY))
        for ascending in (True, False):
            queries.append(Query(f"SORT {field} ascending={ascending}",
                                 [{"type": "SORT", "fieldName": field,
                                   "ascending": ascending}],
                                 True, SORT_QUERY))
        queries.append(Query(f"DISTINCT {field}",
                             [{"type": "DISTINCT", "fieldName": field}],
                             False, RESHAPING_QUERY))
        queries.append(Query(f"GROUP_BY {field}",
                             [{"type": "GROUP_BY", "fieldName": field}],
                             False, RESHAPING_QUERY))
    return queries


def queryable_fields(indexed: list) -> list:
    return sorted(set(indexed) | {PRIMARY_KEY_FIELD})


def build_matrices(outcome, rng, operands_per_operator: int = 2) -> dict:
    matrices = {}
    for db, coll in outcome.live():
        fields = queryable_fields(outcome.indexed_fields(db, coll))
        matrices[(db, coll)] = queries_for(rng, fields, operands_per_operator)
    return matrices


def index_vs_scan(executor, matrices: dict, stats: Stats) -> list:
    divergences = []
    for (db, coll), queries in sorted(matrices.items()):
        stats.bump("indexed_collections")
        for query in queries:
            stats.bump("index_vs_scan_queries")
            indexed = answer_of(aggregate(executor, db, coll, query.steps), query.ordered)
            scanned = answer_of(aggregate(executor, db, coll, forced_scan(query.steps)),
                                query.ordered)
            if indexed != scanned:
                divergences.append(
                    Divergence("index-vs-scan", db, coll, query.label, indexed, scanned))
    return divergences


def count_vs_rows(executor, matrices: dict, stats: Stats) -> list:
    divergences = []
    for (db, coll), queries in sorted(matrices.items()):
        for query in queries:
            if query.kind != FILTER_QUERY:
                continue
            stats.bump("count_vs_rows_queries")
            counted = count_of(aggregate(executor, db, coll,
                                         query.steps + [{"type": "COUNT"}]))
            response = aggregate(executor, db, coll, query.steps)
            rows = rows_of(response)
            listed = len(rows) if rows is not None else (
                "ERROR", response.get("errorCode") or response.get("status"))
            if counted != listed:
                divergences.append(
                    Divergence("count-vs-rows", db, coll, query.label, counted, listed))
    return divergences


def snapshot(executor, matrices: dict) -> dict:
    taken = {}
    for (db, coll), queries in sorted(matrices.items()):
        for query in queries:
            taken[(db, coll, query.label)] = answer_of(
                aggregate(executor, db, coll, query.steps), query.ordered)
    return taken


def warm_vs_cold(executor, matrices: dict, stats: Stats) -> list:
    warm = snapshot(executor, matrices)
    executor.restart()
    cold = snapshot(executor, matrices)
    divergences = []
    for key, warm_answer in sorted(warm.items()):
        stats.bump("warm_vs_cold_queries")
        cold_answer = cold.get(key)
        if warm_answer != cold_answer:
            db, coll, label = key
            divergences.append(
                Divergence("warm-vs-cold", db, coll, label, warm_answer, cold_answer))
    return divergences
