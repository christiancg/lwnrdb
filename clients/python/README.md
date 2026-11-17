# LWNRDB Python client

The Python client for [LWNRDB](https://github.com/christiancg/lwnrdb). It keeps a pool of authenticated connections
and gives you a fluent query builder, transactions, live queries and the server's custom value types, both
synchronous and with asyncio. It needs Python 3.11 or newer and has no runtime dependencies.

## Installation

```bash
pip install lwnrdb-client
```

The package ships its type information, so mypy and pyright can check your code against it.

## Quick start

```python
import os

from lwnrdb import ClientSettings, Credentials, LwnrdbClient, Order, ServerAddress, field

settings = ClientSettings(
    hosts=(ServerAddress("localhost", 8989),),
    credentials=Credentials("admin", os.environ["LWNRDB_PASSWORD"]),
)

with LwnrdbClient.connect(settings) as client:
    shop = client.database("shop")
    shop.create()
    orders = shop.collection("orders")
    orders.create()

    orders.save({"_id": "o-1", "status": "active", "total": 120})
    order = orders.find_by_id("o-1")

    page = (
        orders.query()
        .filter(field("status").eq("active") & (field("total") > 100))
        .sort("total", Order.DESC)
        .limit(10)
        .to_list()
    )
```

A collection has to exist before you write to it. Besides `save` and `find_by_id`, a collection has `insert`,
`save_all`, `delete`, `update_where` and `delete_where`. Share one client across your application. The pool size is how
many requests run at once.

## Sync or asyncio

Every class has an async twin with the same methods: `AsyncLwnrdbClient`, `AsyncCollection`, `AsyncQuery` and so on.

```python
from lwnrdb import AsyncLwnrdbClient

async with await AsyncLwnrdbClient.connect(settings) as client:
    orders = client.database("shop").collection("orders")
    await orders.save({"_id": "o-2", "status": "new", "total": 30})
    count = await orders.query().filter(field("status").eq("new")).count()
```

The sync client runs the async one on a background thread and is safe to use from many threads. Inside a coroutine,
use the async client.

## Settings

The ones you usually set are `hosts` (tried in turn), `credentials`, `tls` and `pool_size` (8 by default). Durations
are in seconds. Timeouts, the retry policy and the rest are in the API reference.

## Typed collections

Pass a dataclass to `collection` and documents map to it both ways. A field named `id` maps to `_id`.

```python
from dataclasses import dataclass
from datetime import datetime

from lwnrdb import GeoPoint


@dataclass
class Store:
    name: str
    location: GeoPoint
    opened_at: datetime
    id: str | None = None


stores = shop.collection("stores", Store)
stores.save(Store("Centro", GeoPoint(-34.6, -58.38), datetime(2024, 1, 1, 9, 0)))
open_ones: list[Store] = stores.query().filter(field("opened_at") < datetime(2025, 1, 1)).to_list()
```

For any other type, such as a pydantic model, pass `codec=` an object with `encode(value) -> dict` and
`decode(document) -> value`.

## Custom value types

The server has four value types of its own. The client maps them to Python values both ways.

| Server value                  | Python value                        | Example                                                                                |
| ----------------------------- | ----------------------------------- | -------------------------------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | naive `datetime` or `LocalDateTime` | `field("placedAt") >= datetime(2024, 1, 1)`                                            |
| `#time(09:30)`                | `time` or `LocalTime`               | `field("opensAt") < time(12, 0)`                                                       |
| `#geo(lat,lng)`               | `GeoPoint`                          | `distance("location", GeoPoint(40.71, -74), GeoDistanceComparator.SMALLER_THAN, 1000)` |
| `#vector(v0,v1,...)`          | `Vector`                            | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)`                                |

The server's date-time has no zone, so an aware `datetime` is refused unless you set `temporal_zone`. The server
stores numbers as doubles, so keep integers past 2^53 in strings. `nearest` is approximate. Add `.exact()` to get the
true top `k`.

## Queries

`collection.query()` starts a pipeline. Queries are immutable, so every step returns a new one.

| Step                           | Method                                                                                                                                       |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `filter(...)` with `field(...).eq/ne/gt/gte/lt/lte/in_/not_in/contains`, `and_/or_/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP`                          | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)`                                                   |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `group_by(field)`, `distinct()`, `distinct(field)`, `join(collection, local_field, remote_field, as_field)`                                  |
| `SORT`, `SKIP`, `LIMIT`        | `sort(field, Order.ASC \| Order.DESC)`, `skip(n)`, `limit(n)`                                                                                |
| `REDUCE`                       | `reduce(script)`, `reduce(script, initial_value, result_field)`                                                                              |
| `COUNT`                        | `count()`                                                                                                                                    |

Run it with `to_list()`, `to_documents()`, `first()`, `count()` or `analyze()`.

`<`, `<=`, `>` and `>=` on a `field(...)` are shortcuts for `lt`, `lte`, `gt` and `gte`, and filters combine with `&`,
`|`, `^` and `~`. Equality is only `eq` and `ne`, since `==` is a plain Python comparison.

## Transactions

```python
with client.transaction() as tx:
    accounts = tx.collection("bank", "accounts")
    accounts.save(debit)
    accounts.save(credit)
```

The block commits when it ends normally and rolls back when it raises. A transaction holds one connection, its writes
lock their collections until the commit, and the server rolls it back after `transactionIdleTimeoutMs` idle.

## Live queries

```python
class Board:
    def on_update(self, update):
        render(update.results)

    def on_ended(self, reason):
        mark_stale(reason)

    def on_error(self, error):
        log(error)


with orders.listen(Board(), orders.query().filter(field("status").eq("pending"))) as subscription:
    ...
```

A handler is a callable or an object with `on_update` and, optionally, `on_ended` and `on_error`. It gets the whole
result right away, then again every time a write changes it, one update at a time. Live queries use their own
connections, not the pool.

## Errors and retries

Every refusal from the server is an `LwnrdbError` with the server's error code. There is a subclass per range:
`AuthenticationError` (401), `ForbiddenError` (403), `NotFoundError` (404), `ConflictError` (409), `ListenEndedError`
(410), `UnavailableError` (503) and `UnknownOutcomeError` (`500-33`, `500-34`).

The client retries [retryable](../../docs/protocol.md#error-codes) codes and reads that lost their connection. It
never retries a write that lost its connection (`ConnectionLostError`), an unknown outcome, or anything inside a
transaction.

## TLS

```python
from lwnrdb import TlsSettings

settings = ClientSettings(
    hosts=(ServerAddress("db.example.com", 8989),),
    tls=TlsSettings(ca_file="lwnrdb-ca.pem"),
)
```

`TlsSettings()` uses the system's certificate authorities, and `insecure=True` trusts any certificate, only for
development. To export the server's certificate as PEM:

```bash
keytool -exportcert -rfc -alias <alias> -keystore lwnrdb.p12 -storetype PKCS12 -storepass <password> > lwnrdb-ca.pem
```

## API reference

The API docs are in [`apidocs/`](apidocs) and in the published wheel's docstrings, so `help()` and your editor show
them.

## Building

```bash
python -m venv .venv && . .venv/bin/activate
pip install --group dev -e .
python build_tools/verify.py
mvn package -DskipTests         # at the repository root, the server jar the integration tests use
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar python -m pytest tests/it -m it
```

## Releasing

Push a `python-client-vX.Y.Z` tag. The release workflow tests the client and publishes it to PyPI.
