# LWNRDB Node.js client

The official Node.js client for [LWNRDB](https://github.com/christiancg/lwnrdb): a pool of authenticated connections, a
fluent query builder, transactions, live queries and the server's custom value types, for JavaScript and TypeScript on
Node.js 22.12 or newer. It has no runtime dependencies.

## Installation

```bash
npm install lwnrdb-client
```

The package is ESM with bundled type declarations. CommonJS code loads it with `require`, which Node.js supports for ES
modules since 22.12:

```js
import { LwnrdbClient, field } from "lwnrdb-client";
```

```js
const { LwnrdbClient, field } = require("lwnrdb-client");
```

## Quick start

```ts
import { and, field, LwnrdbClient, Order } from "lwnrdb-client";

const client = await LwnrdbClient.connect({
    hosts: [{ host: "localhost", port: 8989 }],
    credentials: { username: "admin", password: process.env.LWNRDB_PASSWORD ?? "" },
});

const shop = client.database("shop");
await shop.create();
const orders = shop.collection("orders");
await orders.create();

await orders.save({ _id: "o-1", status: "active", total: 120 });
const order = await orders.findById("o-1");

const page = await orders
    .query()
    .filter(and(field("status").eq("active"), field("total").gt(100)))
    .sort("total", Order.DESC)
    .limit(10)
    .toArray();

await client.close();
```

A collection must exist before its first write: a save into a missing one is refused with `404-11`.

The client holds a pool of connections (8 by default). Each request borrows one connection until its reply arrives, so
the pool size bounds how many requests run at once; raise `poolSize` when more need to run in parallel. Idle
connections do not keep the process alive, and `await using client = await LwnrdbClient.connect(...)` closes the client
at the end of its block.

## Settings

| Setting             | Default                               |                                                                    |
| ------------------- | ------------------------------------- | ------------------------------------------------------------------ |
| `hosts`             | `[{ host: "localhost", port: 8989 }]` | tried in turn when a connection is opened                          |
| `credentials`       | none                                  | `{ username, password }`; every connection authenticates with them |
| `tls`               | off                                   | see [TLS](#tls)                                                    |
| `poolSize`          | `8`                                   | connections shared by requests                                     |
| `connectTimeoutMs`  | `10000`                               | per connection attempt, TLS handshake included                     |
| `requestTimeoutMs`  | `330000`                              | a request with no reply in time closes its connection              |
| `acquireTimeoutMs`  | `30000`                               | how long a request waits for a free connection                     |
| `shutdownTimeoutMs` | `10000`                               | how long `close()` waits for requests in flight                    |
| `maxReplyBytes`     | 256 MB                                | a longer reply fails its request                                   |
| `retryPolicy`       | 5 attempts, 50 ms doubling to 2 s     | see [Errors and retries](#errors-and-retries)                      |
| `temporalZone`      | none                                  | the IANA zone a `Date` is converted through                        |

## Typed collections

`collection<T>(name)` types what you save as `T` and what you read as `T & { _id: string }`. The type is a compile-time
promise only: documents are stored as they are.

```ts
interface Order {
    readonly _id?: string;
    readonly status: "active" | "closed";
    readonly total: number;
    readonly placedAt: LocalDateTime;
}

const orders = shop.collection<Order>("orders");
await orders.save({ status: "active", total: 120, placedAt: LocalDateTime.of(2024, 1, 1, 10, 0) });
const active = await orders.query().filter(field("status").eq("active")).toArray();
```

Pass a `DocumentCodec` to map documents to your own classes in both directions:

```ts
const invoices = shop.collection("invoices", {
    encode: (invoice: Invoice) => ({ _id: invoice.id, cents: invoice.cents }),
    decode: (document) => new Invoice(String(document._id), Number(document.cents)),
});
```

## Custom value types

The server stores four value types as tagged strings; the client maps them to classes in both directions, at any depth
of a document, a filter value or a script argument.

| Server value                  | Class           | Example                                                                                   |
| ----------------------------- | --------------- | ----------------------------------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | `LocalDateTime` | `field("placedAt").gte(LocalDateTime.of(2024, 1, 1))`                                     |
| `#time(09:30)`                | `LocalTime`     | `field("opensAt").lt(LocalTime.of(12, 0))`                                                |
| `#geo(lat,lng)`               | `GeoPoint`      | `distance("location", GeoPoint.of(40.71, -74), GeoDistanceComparator.SMALLER_THAN, 1000)` |
| `#vector(v0,v1,...)`          | `Vector`        | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)`                                   |

- The server's date-time has no zone. A JavaScript `Date` is an instant, so it is refused unless the client is
  configured with `temporalZone`; it is then stored as that zone's wall-clock time. Read values are always
  `LocalDateTime`s; `toDate(zone)` turns one back into a `Date`.
- A plain string that looks like a custom value (`#name(...)`) is refused, because the server would read it as one.
- A number must be finite, and an integer must be a safe integer: the server stores numbers as doubles, so store a
  larger integer as a string.
- `nearest` is an approximate, index-backed search; add `.exact()` to scan every row and get the true top `k`.

## Queries

`collection.query()` starts an immutable pipeline; every step returns a new query.

| Step                           | Method                                                                                                                                  |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `filter(...)` with `field(...).eq/ne/gt/gte/lt/lte/in/notIn/contains`, `and/or/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP`                          | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)`                                              |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `groupBy(field)`, `distinct()`, `distinct(field)`, `join(collection, localField, remoteField, asField)`                                 |
| `SORT`, `SKIP`, `LIMIT`        | `sort(field, Order.ASC \| Order.DESC)`, `skip(n)`, `limit(n)`                                                                           |
| `REDUCE`                       | `reduce(script)`, `reduce(script, initialValue, resultField)`                                                                           |
| `COUNT`                        | `count()`                                                                                                                               |

Run a query with `toArray()`, `toDocuments()` (for rows of another shape, such as `groupBy`), `first()`, `count()` or
`analyze()`. `dirtyRead()` reads without the collection lock. `in` and `notIn` take their values as separate arguments:
`field("status").in("new", "paid")`.

## Transactions

```ts
const id = await client.transaction(async (tx) => {
    const accounts = tx.collection("bank", "accounts");
    await accounts.save(debit);
    await accounts.save(credit);
    return tx.id;
});
```

The body commits when its promise resolves and rolls back when it rejects; the body's error is rethrown.
`beginTransaction()` hands the transaction to you instead, and `await using` rolls it back if it is still open at the
end of the block. A transaction keeps one connection until it ends. Its writes take the collection's write lock, so a
read of that collection from outside the transaction waits for the commit (pass `{ dirtyRead: true }` to look without
waiting). The server rolls back a transaction that is idle longer than `transactionIdleTimeoutMs` (30 s by default); its
next request fails with `409-16`.

## Live queries

```ts
const subscription = await orders.listen(orders.query().filter(field("status").eq("pending")), {
    onUpdate: (update) => render(update.results),
    onEnded: (reason) => markStale(reason),
    onError: (error) => log(error),
});

await subscription.close();
```

The handler receives the full result immediately (`update.initial`), then again whenever a write changes it. Updates for
the listens of one connection are delivered in order, one at a time: an async `onUpdate` finishes before the next update
starts. Listens use their own connections, never pooled ones, and spread over more connections when the server's
per-connection limit is reached; those connections keep the process alive until the subscriptions close. `onEnded` is
called when the server ends a listen (for example, its collection was dropped); `onError` when a handler threw or the
connection was lost, which ends every subscription on it.

## Errors and retries

Every server refusal is an `LwnrdbError` carrying the server's code (`rawCode`, and `code` for its catalogue entry),
subclassed by range: `AuthenticationError` (401), `ForbiddenError` (403), `NotFoundError` (404), `ConflictError` (409),
`ListenEndedError` (410), `UnavailableError` (503) and `UnknownOutcomeError` (`500-33`, `500-34`). `ErrorCode` holds
every code by name.

The client retries, per `retryPolicy`:

- a refusal whose code is retryable (`503-2/4/5/9/10/16/17/18/19/20/21`);
- a read (`FIND_BY_ID`, `AGGREGATE`, `LIST_*`, `GET_*`, `SERVER_INFO`) whose connection was lost.

It never retries a write that lost its connection (`ConnectionLostError`: the outcome is unknown), an
`UnknownOutcomeError`, or anything inside a transaction. A request with no reply within `requestTimeoutMs` closes its
connection and rejects with `RequestTimeoutError`.

## TLS

```ts
const client = await LwnrdbClient.connect({
    hosts: [{ host: "db.example.com", port: 8989 }],
    tls: { ca: readFileSync("lwnrdb-ca.pem") },
});
```

`tls: {}` uses Node's certificate authorities, `ca` trusts the PEM certificates you give it, and `insecure: true`
trusts any certificate and skips the host-name check (development only; it emits a warning). Node has no PKCS12 trust
store, so export the server's certificate as PEM:

```bash
keytool -exportcert -rfc -alias <alias> -keystore lwnrdb.p12 -storetype PKCS12 -storepass <password> > lwnrdb-ca.pem
```

## API reference

Every public declaration carries its documentation in the published `.d.ts` files, so editors show it on hover. The
text lives in [`apidocs/`](apidocs), one Markdown file per export with one `## member` section per public member,
because the sources carry no comments. `npm run build` compiles, then `build-tools/inject-docs.ts` writes each section
into the declarations, and fails when a public member has no section or a section names nothing.

## Building

```bash
npm ci
npm run verify                  # format check, ESLint, type check, unit tests with 95% coverage, build
npm run consumers               # packs the client and checks the ESM, CommonJS and TypeScript consumers
mvn package -DskipTests         # at the repository root: the server jar the integration tests run
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar npm run test:it
```

The integration tests start their own servers on free ports and need `java` on the `PATH`.

## Releasing

Push a `typescript-client-vX.Y.Z` tag. `.github/workflows/release-typescript-client.yml` runs the unit and integration
tests against a server built from the same commit, then publishes the package to npm with provenance (a version with a
pre-release suffix goes to the `next` tag) and creates a GitHub release.

It needs one repository secret, `NPM_TOKEN`: a granular access token from npmjs.com (Access Tokens → Generate New Token
→ Granular) with read and write access to `lwnrdb-client`. Once the first release exists, npm's trusted publishing can
replace it: add the repository and workflow as a trusted publisher in the package's settings on npmjs.com, then remove
the `NODE_AUTH_TOKEN` line from the workflow and the secret.
