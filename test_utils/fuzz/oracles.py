import json
import random
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

CONTAINER_OPERATORS = ["EQUALS", "NOT_EQUALS"]

EMPTY_RESULT_CODE = "404-3"

PRIMARY_KEY_FIELD = "_id"

CONJUNCTION_TYPES = ["AND", "OR", "XOR", "NOR", "NAND"]

FILTER_QUERY = "FILTER"
SORT_QUERY = "SORT"
RESHAPING_QUERY = "RESHAPING"
REDUCE_QUERY = "REDUCE"

GEO_TARGET = "#geo(0,0)"
GEO_POLYGON = ["#geo(-10,-10)", "#geo(-10,10)", "#geo(10,10)", "#geo(10,-10)"]
GEO_DATELINE_POLYGON = ["#geo(-10,179)", "#geo(-10,-179)", "#geo(10,-179)", "#geo(10,179)"]
VECTOR_TARGET = "#vector(1.0,0.0,0.0)"

NON_COMMUTATIVE_REDUCE_SCRIPT = "export default (acc, doc) => acc + '|' + doc._id;"

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


def fold_order_query() -> Query:
    return Query("REDUCE fold of _id",
                 [{"type": "REDUCE", "script": NON_COMMUTATIVE_REDUCE_SCRIPT,
                   "initialValue": "", "resultField": "folded"}],
                 True, REDUCE_QUERY)


def leaf_operator(rng, field: str) -> dict:
    operator = rng.choice(SCALAR_OPERATORS)
    return {"fieldOperatorType": operator, "field": field, "value": values.operand(rng)}


def conjunction_step(kind: str, leaves: list) -> dict:
    return {"type": "FILTER", "operator": {"conjunctionType": kind, "operators": leaves}}


def label_of(leaves: list) -> str:
    return ", ".join(f"{leaf['field']} {leaf['fieldOperatorType']} "
                     f"{values.to_json_text(leaf['value'])}" for leaf in leaves)


# The index resolves a conjunction with set algebra and the scan with per-document grouping, so the two
# are independent implementations of one answer. Duplicate children separate them the hardest.
def conjunction_queries(rng, fields: list) -> list:
    queries = []
    for kind in CONJUNCTION_TYPES:
        for width in (2, 3):
            leaves = [leaf_operator(rng, rng.choice(fields)) for _ in range(width)]
            queries.append(Query(f"FILTER {kind}({label_of(leaves)})",
                                 [conjunction_step(kind, leaves)], False, FILTER_QUERY))
        repeated = leaf_operator(rng, rng.choice(fields))
        queries.append(Query(f"FILTER {kind}(twice: {label_of([repeated])})",
                             [conjunction_step(kind, [repeated, dict(repeated)])], False, FILTER_QUERY))
        nested = [leaf_operator(rng, rng.choice(fields)) for _ in range(2)]
        outer = leaf_operator(rng, rng.choice(fields))
        queries.append(Query(f"FILTER {kind}(nested OR({label_of(nested)}), {label_of([outer])})",
                             [conjunction_step(kind, [{"conjunctionType": "OR", "operators": nested}, outer])],
                             False, FILTER_QUERY))
    return queries


def map_queries(rng, fields: list) -> list:
    queries = []
    for field in fields:
        condition = leaf_operator(rng, field)
        queries.append(Query(f"MAP condition on {field}",
                             [{"type": "MAP", "operators": [{"fieldName": "mapped", "condition": condition}]}],
                             False, RESHAPING_QUERY))
        queries.append(Query(f"MAP cast {field} to STRING",
                             [{"type": "MAP", "operators": [
                                 {"fieldName": "asText",
                                  "operator": {"type": "CAST", "fieldName": field, "toType": "STRING"}}]}],
                             False, RESHAPING_QUERY))
    for kind in CONJUNCTION_TYPES:
        leaves = [leaf_operator(rng, rng.choice(fields)) for _ in range(2)]
        queries.append(Query(f"MAP {kind} condition",
                             [{"type": "MAP", "operators": [
                                 {"fieldName": "mapped",
                                  "condition": {"conjunctionType": kind, "operators": leaves}}]}],
                             False, RESHAPING_QUERY))
    return queries


def join_queries(fields: list, partners: list) -> list:
    queries = []
    for db, coll in partners:
        for field in fields:
            queries.append(Query(f"JOIN {db}|{coll} on {field}",
                                 [{"type": "JOIN", "joinCollection": coll, "localField": field,
                                   "remoteField": field, "asField": "joined"}],
                                 False, RESHAPING_QUERY))
    return queries


# nearest is an approximate neighbourhood search unless exact is set, so only the exact form may be
# compared against a full scan.
def custom_operator_queries(fields: list) -> list:
    queries = []
    for field in fields:
        if field == PRIMARY_KEY_FIELD:
            continue
        for comparator, distance in (("SMALLER_THAN", 5000000.0), ("GREATER_THAN", 1000.0)):
            queries.append(Query(f"FILTER {field} distance {comparator} {distance}",
                                 [{"type": "FILTER", "operator": {
                                     "customOperatorName": "distance", "field": field,
                                     "value": GEO_TARGET, "comparator": comparator,
                                     "distance": distance}}],
                                 False, FILTER_QUERY))
        for name, polygon in (("box", GEO_POLYGON), ("dateline", GEO_DATELINE_POLYGON)):
            queries.append(Query(f"FILTER {field} within {name}",
                                 [{"type": "FILTER", "operator": {
                                     "customOperatorName": "within", "field": field,
                                     "polygon": polygon}}],
                                 False, FILTER_QUERY))
        queries.append(Query(f"FILTER {field} nearest exact",
                             [{"type": "FILTER", "operator": {
                                 "customOperatorName": "nearest", "field": field,
                                 "value": VECTOR_TARGET, "k": 5, "exact": True}}],
                             False, FILTER_QUERY))
    return queries


def container_queries(rng, fields: list) -> list:
    queries = []
    for field in fields:
        for operator in CONTAINER_OPERATORS:
            operand = values.container_operand(rng)
            queries.append(Query(f"FILTER {field} {operator} {values.to_json_text(operand)}",
                                 [filter_step(field, operator, operand)], False, FILTER_QUERY))
        for operator in LIST_OPERATORS:
            operand = values.container_operand_list(rng)
            queries.append(Query(f"FILTER {field} {operator} {values.to_json_text(operand)}",
                                 [filter_step(field, operator, operand)], False, FILTER_QUERY))
    return queries


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
    queries.extend(container_queries(random.Random(hash(rng.getstate())), fields))
    queries.append(fold_order_query())
    queries.extend(conjunction_queries(rng, fields))
    queries.extend(map_queries(rng, fields))
    queries.extend(custom_operator_queries(fields))
    return queries


def queryable_fields(indexed: list) -> list:
    return sorted(set(indexed) | {PRIMARY_KEY_FIELD})


def build_matrices(outcome, rng, operands_per_operator: int = 2) -> dict:
    matrices = {}
    live = outcome.live()
    for db, coll in live:
        fields = queryable_fields(outcome.indexed_fields(db, coll))
        partners = [other for other in live if other != (db, coll) and other[0] == db]
        matrices[(db, coll)] = (queries_for(rng, fields, operands_per_operator)
                                + join_queries(fields, partners))
    return matrices


def index_vs_scan(executor, matrices: dict, stats: Stats) -> list:
    divergences = []
    for (db, coll), queries in sorted(matrices.items()):
        stats.bump("indexed_collections")
        for query in queries:
            if query.kind == REDUCE_QUERY:
                continue
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
