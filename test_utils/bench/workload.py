import hashlib
import math
import random
from dataclasses import dataclass
from typing import Any, Callable

from engines.base import Engine

PRICE_RANGE = 1000.0
QTY_VALUES = 97
VERSION_STRIDE = 1_000_003


@dataclass(frozen=True)
class Settings:
    docs: int
    batch: int
    payload: int
    concurrency: int
    ops_scale: float
    warmup: float
    seed: int
    durability: str = "relaxed"
    category_size: int = 100
    limit: int = 50

    @property
    def categories(self) -> int:
        return max(1, self.docs // self.category_size)


@dataclass(frozen=True)
class Phase:
    name: str
    ops: int
    warmup: int
    argument: Callable[[random.Random, int], Any]
    execute: Callable[[Engine, Any], Any]
    check: Callable[[Any, Any], bool]
    documents: int = 0


def category(index: int) -> str:
    return f"cat-{index:05d}"


def make_doc(key: str, n: int, settings: Settings, version: int = 0) -> dict:
    rng = random.Random(n * VERSION_STRIDE + version)
    digest = hashlib.sha256(f"{n}:{version}".encode()).hexdigest()
    return {
        "_id": key,
        "name": f"name-{n}-v{version}",
        "category": category(n % settings.categories),
        "price": round(rng.random() * PRICE_RANGE, 2),
        "qty": (n + version) % QTY_VALUES,
        "active": (n + version) % 2 == 0,
        "created": f"2026-{1 + n % 12:02d}-{1 + n % 28:02d}T{n % 24:02d}:00:00Z",
        "payload": (digest * (settings.payload // len(digest) + 1))[:settings.payload],
    }


def _always(_argument, _result) -> bool:
    return True


def _confirmed(_argument, result) -> bool:
    return result is True


def phases(settings: Settings) -> list:
    def scaled(base: int) -> int:
        return max(settings.concurrency, int(base * settings.ops_scale))

    def warm(ops: int) -> int:
        return int(ops * settings.warmup)

    span = PRICE_RANGE * 4 * settings.limit / settings.docs
    inserts = scaled(20_000)
    reads = scaled(50_000)
    updates = scaled(20_000)
    equalities = scaled(10_000)
    ranges = scaled(5_000)
    counts = scaled(2_000)
    scans = scaled(200)

    def bulk_argument(_rng, n):
        start = n * settings.batch
        return [make_doc(f"d{i}", i, settings) for i in range(start, min(start + settings.batch, settings.docs))]

    def random_key(rng, _n):
        return f"d{rng.randrange(settings.docs)}"

    def update_argument(rng, n):
        k = rng.randrange(settings.docs)
        return make_doc(f"d{k}", k, settings, version=n + 1)

    def range_argument(rng, _n):
        low = round(rng.uniform(0, PRICE_RANGE - span), 2)
        return low, round(low + span, 2)

    def range_check(argument, rows):
        low, high = argument
        prices = [row["price"] for row in rows]
        return len(rows) == settings.limit and prices == sorted(prices) and all(low <= p < high for p in prices)

    def equality_check(argument, rows):
        return len(rows) == settings.limit and all(row["category"] == argument for row in rows)

    def count_check(argument, count):
        return count > 0 if argument < PRICE_RANGE * 0.99 else count >= 0

    return [
        Phase("bulk_insert", math.ceil(settings.docs / settings.batch), 0, bulk_argument,
              lambda engine, docs: engine.bulk_insert(docs), _always, documents=settings.docs),
        Phase("insert", inserts, warm(inserts), lambda _rng, n: make_doc(f"i{n}", settings.docs + n, settings),
              lambda engine, doc: engine.insert(doc), _always),
        Phase("get_by_id", reads, warm(reads), random_key, lambda engine, key: engine.get(key),
              lambda key, doc: doc is not None and doc["_id"] == key),
        Phase("update_by_id", updates, warm(updates), update_argument, lambda engine, doc: engine.update(doc),
              _confirmed),
        Phase("find_eq_indexed", equalities, warm(equalities),
              lambda rng, _n: category(rng.randrange(settings.categories)),
              lambda engine, value: engine.find_eq(value, settings.limit), equality_check),
        Phase("find_range_sorted", ranges, warm(ranges), range_argument,
              lambda engine, bounds: engine.find_range(bounds[0], bounds[1], settings.limit), range_check),
        Phase("count_range_indexed", counts, warm(counts), lambda rng, _n: round(rng.uniform(0, PRICE_RANGE), 2),
              lambda engine, low: engine.count_range(low), count_check),
        Phase("scan_count_unindexed", scans, warm(scans), lambda rng, _n: rng.randrange(QTY_VALUES),
              lambda engine, qty: engine.scan_count(qty), lambda _qty, count: count > 0),
        Phase("delete_by_id", inserts, warm(inserts), lambda _rng, n: f"i{n}",
              lambda engine, key: engine.delete(key), _confirmed),
    ]
