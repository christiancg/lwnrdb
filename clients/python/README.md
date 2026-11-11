# LWNRDB Python client

The official Python client for [LWNRDB](https://github.com/christiancg/lwnrdb): a pool of authenticated connections, a
fluent query builder, transactions, live queries and the server's custom value types, for Python 3.11 or newer, both
synchronous and asyncio. It has no runtime dependencies.

## Installation

```bash
pip install lwnrdb-client
```

The package ships its type information (`py.typed`), so mypy and pyright check your queries and documents.

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

A collection must exist before its first write: a save into a missing one is refused with `404-11`.

The client holds a pool of connections (8 by default). Each request borrows one connection until its reply arrives, so
the pool size bounds how many requests run at once; raise `pool_size` when more need to run in parallel. Share one
client across your application and close it when you are done (`with` does it for you).

## Sync or asyncio

Every class comes in two forms with the same methods: `LwnrdbClient`, `Database`, `Collection`, `Query`, ... block and
return values; `AsyncLwnrdbClient`, `AsyncDatabase`, `AsyncCollection`, `AsyncQuery`, ... are awaited.

```python
from lwnrdb import AsyncLwnrdbClient

async with await AsyncLwnrdbClient.connect(settings) as client:
    orders = client.database("shop").collection("orders")
    await orders.save({"_id": "o-2", "status": "new", "total": 30})
    count = await orders.query().filter(field("status").eq("new")).count()
```

Both share one implementation: the sync client runs the asyncio client on a private event-loop thread, which is a
daemon, so a forgotten client never keeps the process alive. The sync client is safe to use from many threads. Calling
it from inside a coroutine works too, but blocks that event loop; use the async client there.

## Settings

All durations are seconds.

| Setting            | Default                                 |                                                                   |
| ------------------ | --------------------------------------- | ----------------------------------------------------------------- |
| `hosts`            | `(ServerAddress("localhost", 8989),)`   | tried in turn when a connection is opened                         |
| `credentials`      | none                                    | `Credentials(username, password)`; every connection authenticates |
| `tls`              | off                                     | see [TLS](#tls)                                                   |
| `pool_size`        | `8`                                     | connections shared by requests                                    |
| `connect_timeout`  | `10.0`                                  | per connection attempt, TLS handshake included                    |
| `request_timeout`  | `330.0`                                 | a request with no reply in time closes its connection             |
| `acquire_timeout`  | `30.0`                                  | how long a request waits for a free connection                    |
| `shutdown_timeout` | `10.0`                                  | how long `close()` waits for requests in flight                   |
| `max_reply_bytes`  | 256 MiB                                 | a longer reply fails its request                                  |
| `retry_policy`     | 5 attempts, 0.05 s doubling to 2 s      | see [Errors and retries](#errors-and-retries)                     |
| `temporal_zone`    | none                                    | the IANA zone an aware `datetime` is converted through            |

## Typed collections

Pass a dataclass to `collection` and documents map to it in both directions. A field named `id` maps to `_id`; nested
dataclasses, lists, tuples, dicts, enums (by value), optionals and every custom value type are supported, and unknown
keys are ignored when reading.

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

A vector held as a list of floats is marked with `Annotated[list[float], AsVector]`.

Pass `codec=` to map any other type. A `DocumentCodec` has `encode(value) -> dict` and `decode(document) -> value`;
for pydantic models:

```python
class PydanticCodec:
    def __init__(self, model):
        self.model = model

    def encode(self, value):
        return value.model_dump(by_alias=True, exclude_none=True)

    def decode(self, document):
        return self.model.model_validate(document)


invoices = shop.collection("invoices", codec=PydanticCodec(Invoice))
```

## Custom value types

The server stores four value types as tagged strings; the client maps them to Python values in both directions, at any
depth of a document, a filter value or a script argument.

| Server value                  | Python value                         | Example                                                                                 |
| ----------------------------- | ------------------------------------ | --------------------------------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | naive `datetime` or `LocalDateTime`  | `field("placedAt") >= datetime(2024, 1, 1)`                                             |
| `#time(09:30)`                | `time` or `LocalTime`                | `field("opensAt") < time(12, 0)`                                                        |
| `#geo(lat,lng)`               | `GeoPoint`                           | `distance("location", GeoPoint(40.71, -74), GeoDistanceComparator.SMALLER_THAN, 1000)` |
| `#vector(v0,v1,...)`          | `Vector`                             | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)`                                 |

- The server's date-time has no zone. An aware `datetime` is an instant, so it is refused unless the client is
  configured with `temporal_zone`; it is then stored as that zone's wall-clock time. On Windows, `zoneinfo` needs the
  `tzdata` package.
- Reads return a plain `datetime` or `time` when the value fits one. The server keeps nanoseconds and years beyond
  9999; such a value is read as a `LocalDateTime` or `LocalTime`, which keeps every digit, so a document read back saves
  unchanged.
- A `date` is refused: the server has no date type.
- A plain string that looks like a custom value (`#name(...)`) is refused, because the server would read it as one.
- A number must be finite, and an integer must fit in 2^53: the server stores numbers as doubles, so store a larger
  integer as a string. `Decimal`, `bytes` and sets have no wire form and are refused.
- `nearest` is an approximate, index-backed search; add `.exact()` to scan every row and get the true top `k`.

## Queries

`collection.query()` starts an immutable pipeline; every step returns a new query, so a query can be reused.

| Step                           | Method                                                                                                                                          |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `filter(...)` with `field(...).eq/ne/gt/gte/lt/lte/in_/not_in/contains`, `and_/or_/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP`                          | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)`                                                      |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `group_by(field)`, `distinct()`, `distinct(field)`, `join(collection, local_field, remote_field, as_field)`                                     |
| `SORT`, `SKIP`, `LIMIT`        | `sort(field, Order.ASC \| Order.DESC)`, `skip(n)`, `limit(n)`                                                                                   |
| `REDUCE`                       | `reduce(script)`, `reduce(script, initial_value, result_field)`                                                                                 |
| `COUNT`                        | `count()`                                                                                                                                       |

Run a query with `to_list()`, `to_documents()` (for rows of another shape, such as `group_by`), `first()`, `count()` or
`analyze()`. `dirty_read()` reads without the collection lock. `in_` and `not_in` take their values as separate
arguments: `field("status").in_("new", "paid")`.

### Filters as operators

`<`, `<=`, `>` and `>=` on `field(...)` are the same as `lt`, `lte`, `gt` and `gte`, and filters combine with `&` (AND),
`|` (OR), `^` (XOR) and `~` (NOR of one filter, which is NOT):

```python
active_big = (field("status").eq("active")) & (field("total") > 100)
not_cheap = ~(field("total") < 10)
```

Equality is `eq` and `ne` only. `field("status") == "active"` is a plain comparison in Python that evaluates to
`False`, and `filter` refuses it with a message that points to `eq`. Overloading `==` would break `in`, dict keys and
truthiness of the condition objects.

`a & b & c` and `a | b | c` build one three-operand conjunction. `^` does not: the server's XOR matches exactly one
operand, so `a ^ b ^ c` nests; write `xor(a, b, c)` for exactly one of three. `~(x < 5)` keeps documents that have no
`x` at all, which `x >= 5` would not.

## Transactions

```python
with client.transaction() as tx:
    accounts = tx.collection("bank", "accounts")
    accounts.save(debit)
    accounts.save(credit)
```

The block commits when it ends normally and rolls back when it raises; the block's exception propagates.
`begin_transaction()` hands the transaction to you instead, with `commit()` and `rollback()`; used as a context manager
it rolls back if it is still open at the end of the block. The async client uses `async with client.transaction() as
tx:`. A transaction keeps one connection until it ends. Its writes take the collection's write lock, so a read of that
collection from outside the transaction waits for the commit (pass `dirty_read=True` to look without waiting). The
server rolls back a transaction that is idle longer than `transactionIdleTimeoutMs` (30 s by default); its next request
fails with `409-16`.

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

A handler is a callable that takes a `ListenUpdate`, or an object with `on_update` and optionally `on_ended` and
`on_error`. It receives the full result immediately (`update.initial`), then again whenever a write changes it. Updates
for the listens of one connection are delivered in order, one at a time.

- In the async client a handler method may be a coroutine; the next update waits for it.
- In the sync client handlers run on a dedicated `lwnrdb-listen` thread, never on the client's event loop, so a slow
  handler delays only later updates and a handler may call the client.

Listens use their own connections, never pooled ones, and spread over more connections when the server's
per-connection limit is reached. `on_ended` is called when the server ends a listen (for example, its collection was
dropped); `on_error` when a handler raised or the connection was lost, which ends every subscription on it.

## Errors and retries

Every server refusal is an `LwnrdbError` carrying the server's code (`raw_code`, and `code` for its catalogue entry),
subclassed by range: `AuthenticationError` (401), `ForbiddenError` (403), `NotFoundError` (404), `ConflictError` (409),
`ListenEndedError` (410), `UnavailableError` (503) and `UnknownOutcomeError` (`500-33`, `500-34`). `ErrorCode` holds
every code by name.

The client retries, per `retry_policy`:

- a refusal whose code is retryable (`503-2/4/5/9/10/16/17/18/19/20/21`);
- a read (`FIND_BY_ID`, `AGGREGATE`, `LIST_*`, `GET_*`, `SERVER_INFO`) whose connection was lost.

It never retries a write that lost its connection (`ConnectionLostError`: the outcome is unknown), an
`UnknownOutcomeError`, or anything inside a transaction. A request with no reply within `request_timeout` closes its
connection and raises `RequestTimeoutError`. Cancelling an async request after it was sent closes its connection too,
for the same reason.

## TLS

```python
from lwnrdb import TlsSettings

settings = ClientSettings(
    hosts=(ServerAddress("db.example.com", 8989),),
    tls=TlsSettings(ca_file="lwnrdb-ca.pem"),
)
```

`TlsSettings()` uses the system's certificate authorities, `ca_file` or `ca_data` trust the PEM certificates you give
it, `context` takes a ready `ssl.SSLContext`, and `insecure=True` trusts any certificate and skips the host-name check
(development only; it warns with `InsecureTlsWarning`). Export the server's certificate as PEM:

```bash
keytool -exportcert -rfc -alias <alias> -keystore lwnrdb.p12 -storetype PKCS12 -storepass <password> > lwnrdb-ca.pem
```

## API reference

Every public class, method and function carries its documentation as a docstring in the published wheel, so `help()`
and editors show it. The text lives in [`apidocs/`](apidocs), one Markdown file per public class (a sync class shares
its `Async` twin's file) plus `functions.md`, because the sources carry no comments. The wheel build
(`hatch_build.py`) writes each section into a copy of the sources and fails when a public member has no section or a
section names nothing.

## Building

```bash
python -m venv .venv && . .venv/bin/activate
pip install --group dev -e .
python build_tools/verify.py    # format, lint, mypy --strict, unit tests with 95% coverage, build, consumers
mvn package -DskipTests         # at the repository root: the server jar the integration tests run
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar python -m pytest tests/it -m it
```

`pip install --group` needs pip 25.1 or newer. The integration tests start their own servers on free ports and need
`java` on the `PATH`. The error-code and operation tables are generated from `clients/protocol`:
`python build_tools/catalogue.py --write` regenerates them.

## Releasing

Push a `python-client-vX.Y.Z` tag (a PEP 440 version, such as `python-client-v1.2.0rc1`).
`.github/workflows/release-python-client.yml` runs the unit and integration tests against a server built from the same
commit, then publishes the package to PyPI and creates a GitHub release (a pre-release for a version with a suffix).

It uses PyPI's trusted publishing, so it needs no secret: on pypi.org, add a trusted publisher for the project
`lwnrdb-client` with this repository, the workflow `release-python-client.yml` and the environment `pypi` (a pending
publisher works before the first release).
