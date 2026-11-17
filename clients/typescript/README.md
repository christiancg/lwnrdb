# LWNRDB Node.js client

The JavaScript and TypeScript client for [LWNRDB](https://github.com/christiancg/lwnrdb). It keeps a pool of
authenticated connections and gives you a fluent query builder, transactions, live queries and the server's custom
value types. It needs Node.js 22.12 or newer and has no runtime dependencies.

## Installation

```bash
npm install lwnrdb-client
```

The package is ESM with its type declarations included. CommonJS code can load it with `require`.

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

A collection has to exist before you write to it. Besides `save` and `findById`, a collection has `insert`, `saveAll`,
`delete`, `updateWhere` and `deleteWhere`. The pool size is how many requests run at once.

## Settings

The ones you usually set are `hosts` (tried in turn), `credentials` (`{ username, password }`), `tls` and `poolSize`
(8 by default). Timeouts, the retry policy and the rest are in the API reference.

## Typed collections

`collection<T>(name)` types what you save as `T` and what you read as `T & { _id: string }`. It is only a compile-time
type.

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

To map documents to your own classes, pass a `DocumentCodec` with `encode` and `decode` as the second argument.

## Custom value types

The server has four value types of its own. The client maps them to classes both ways.

| Server value                  | Class           | Example                                                                                   |
| ----------------------------- | --------------- | ----------------------------------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | `LocalDateTime` | `field("placedAt").gte(LocalDateTime.of(2024, 1, 1))`                                     |
| `#time(09:30)`                | `LocalTime`     | `field("opensAt").lt(LocalTime.of(12, 0))`                                                |
| `#geo(lat,lng)`               | `GeoPoint`      | `distance("location", GeoPoint.of(40.71, -74), GeoDistanceComparator.SMALLER_THAN, 1000)` |
| `#vector(v0,v1,...)`          | `Vector`        | `nearest("embedding", Vector.of(0.12, 0.44, 0.91), 10)`                                   |

The server's date-time has no zone, so a `Date` is refused unless you set `temporalZone`. The server stores numbers as
doubles, so keep integers past the safe range in strings. `nearest` is approximate. Add `.exact()` to get the true top
`k`.

## Queries

`collection.query()` starts a pipeline. Queries are immutable, so every step returns a new one.

| Step                           | Method                                                                                                                                  |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `filter(...)` with `field(...).eq/ne/gt/gte/lt/lte/in/notIn/contains`, `and/or/nor/xor/nand`, `distance`, `within`, `nearest`, `script` |
| `MAP`                          | `map(MapOps.set(field, MidOps...), MapOps.remove(field))`, each optionally `.when(filter)`                                              |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `groupBy(field)`, `distinct()`, `distinct(field)`, `join(collection, localField, remoteField, asField)`                                 |
| `SORT`, `SKIP`, `LIMIT`        | `sort(field, Order.ASC \| Order.DESC)`, `skip(n)`, `limit(n)`                                                                           |
| `REDUCE`                       | `reduce(script)`, `reduce(script, initialValue, resultField)`                                                                           |
| `COUNT`                        | `count()`                                                                                                                               |

Run it with `toArray()`, `toDocuments()`, `first()`, `count()` or `analyze()`.

## Transactions

```ts
const id = await client.transaction(async (tx) => {
    const accounts = tx.collection("bank", "accounts");
    await accounts.save(debit);
    await accounts.save(credit);
    return tx.id;
});
```

The body commits when its promise resolves and rolls back when it rejects. A transaction holds one connection, its
writes lock their collections until the commit, and the server rolls it back after `transactionIdleTimeoutMs` idle.

## Live queries

```ts
const subscription = await orders.listen(orders.query().filter(field("status").eq("pending")), {
    onUpdate: (update) => render(update.results),
    onEnded: (reason) => markStale(reason),
    onError: (error) => log(error),
});

await subscription.close();
```

The handler gets the whole result right away, then again every time a write changes it, one update at a time. Live
queries use their own connections, which keep the process alive until you close the subscriptions.

## Errors and retries

Every refusal from the server is an `LwnrdbError` with the server's error code. There is a subclass per range:
`AuthenticationError` (401), `ForbiddenError` (403), `NotFoundError` (404), `ConflictError` (409), `ListenEndedError`
(410), `UnavailableError` (503) and `UnknownOutcomeError` (`500-33`, `500-34`).

The client retries [retryable](../../docs/protocol.md#error-codes) codes and reads that lost their connection. It
never retries a write that lost its connection (`ConnectionLostError`), an unknown outcome, or anything inside a
transaction.

## TLS

```ts
const client = await LwnrdbClient.connect({
    hosts: [{ host: "db.example.com", port: 8989 }],
    tls: { ca: readFileSync("lwnrdb-ca.pem") },
});
```

`tls: {}` uses Node's certificate authorities, and `insecure: true` trusts any certificate, only for development.
Node can't read a PKCS12 trust store, so export the server's certificate as PEM:

```bash
keytool -exportcert -rfc -alias <alias> -keystore lwnrdb.p12 -storetype PKCS12 -storepass <password> > lwnrdb-ca.pem
```

## API reference

The API docs are in [`apidocs/`](apidocs) and in the published `.d.ts` files, so your editor shows them on hover.

## Building

```bash
npm ci
npm run verify
npm run consumers
mvn package -DskipTests         # at the repository root, the server jar the integration tests use
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar npm run test:it
```

## Releasing

Push a `typescript-client-vX.Y.Z` tag. The release workflow tests the client and publishes it to npm.
