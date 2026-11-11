# LWNRDB .NET client

The official .NET client for [LWNRDB](https://github.com/christiancg/lwnrdb): a pool of authenticated connections, a
fluent query builder, a LINQ provider, transactions, live queries and the server's custom value types, for .NET 10 or
newer. It has no NuGet dependencies.

## Installation

```bash
dotnet add package Lwnrdb.Client
```

## Quick start

```csharp
using Lwnrdb.Client;
using Lwnrdb.Client.Linq;
using Lwnrdb.Client.Query;
using static Lwnrdb.Client.Query.Filters;

await using var client = await LwnrdbClient.ConnectAsync(new ClientSettings
{
    Hosts = [new ServerAddress("localhost", 8989)],
    Credentials = new Credentials("admin", Environment.GetEnvironmentVariable("LWNRDB_PASSWORD") ?? ""),
});

var shop = client.Database("shop");
await shop.CreateAsync();
var orders = shop.Collection<Purchase>("orders");
await orders.CreateAsync();

await orders.SaveAsync(new Purchase { Id = "o-1", Status = "active", Total = 120, PlacedAt = new DateTime(2024, 1, 1, 10, 0, 0) });
var one = await orders.FindByIdAsync("o-1");

var fluent = await orders.Query()
    .Filter(And(Field("status").Eq("active"), Field("total").Gt(100)))
    .Sort("total", Order.Desc)
    .Limit(10)
    .ToListAsync();

var linq = await orders.AsQueryable()
    .Where(o => o.Status == "active" && o.Total > 100)
    .OrderByDescending(o => o.Total)
    .Take(10)
    .ToListAsync();

public sealed class Purchase
{
    public string? Id { get; set; }
    public string? Status { get; set; }
    public double Total { get; set; }
    public DateTime PlacedAt { get; set; }
}
```

`Order` in `Sort` is `Lwnrdb.Client.Query.Order` (`Asc`, `Desc`); if your own model has an `Order` type, alias one of
them with a `using` directive.

## Settings

| Setting           | Default                           |                                                                     |
| ----------------- | --------------------------------- | ------------------------------------------------------------------- |
| `Hosts`           | `localhost:8989`                  | tried in turn when a connection is opened                           |
| `Credentials`     | none                              | every connection authenticates with them                            |
| `Tls`             | off                               | see [TLS](#tls)                                                     |
| `PoolSize`        | `8`                               | connections shared by requests                                      |
| `ConnectTimeout`  | 10 s                              | per connection attempt, TLS handshake included                      |
| `RequestTimeout`  | 330 s                             | a request with no reply in time closes its connection               |
| `AcquireTimeout`  | 30 s                              | how long a request waits for a free connection                      |
| `ShutdownTimeout` | 10 s                              | how long `DisposeAsync` waits for requests in flight                |
| `MaxReplyBytes`   | 256 MB                            | a longer reply fails its request                                    |
| `RetryPolicy`     | 5 attempts, 50 ms doubling to 2 s | see [Errors and retries](#errors-and-retries)                       |
| `TemporalZone`    | none                              | the `TimeZoneInfo` an instant is converted through                  |
| `Json`            | camelCase                         | your `JsonSerializerOptions`, copied and extended with LWNRDB types |

`ConnectAsync` validates the settings and throws `ArgumentException` naming a bad one. Every async method takes a
`CancellationToken`. Cancelling before a request is sent sends nothing; cancelling after it was sent closes its
connection, because the request's outcome is then unknown.

## Typed collections

`Collection<T>(name)` maps documents through `System.Text.Json`. Property names are camelCase by default, and
`[JsonPropertyName]`, `[JsonIgnore]`, your naming policy and your converters apply. A property named `Id` is stored as
`_id` (and omitted when null, so the server generates one); it must be a `string`. Pass `ClientSettings.Json` to use
your own options, including a source-generated `JsonSerializerContext`:

```csharp
var settings = new ClientSettings { Json = new JsonSerializerOptions { TypeInfoResolver = ShopJsonContext.Default } };
```

`Collection(name)` without a type argument works with `Document`, an immutable JSON object with typed getters
(`GetString`, `GetDouble`, `GetDateTime`, `GetGeoPoint`, `GetDocument`, `GetList`, ...) and `Document.Of("_id", "o-1",
"total", 12)` to build one. Implement `IDocumentCodec<T>` to map documents yourself.

## Custom value types

The server stores four value types as tagged strings; the client maps them in both directions, at any depth of a
document, a filter value or a script argument.

| Server value                  | .NET type                                    | Example                                                        |
| ----------------------------- | -------------------------------------------- | -------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | `DateTime`                                   | `Field("placedAt").Gte(new DateTime(2024, 1, 1))`              |
| `#time(09:30)`                | `TimeOnly`                                   | `o => o.OpensAt < new TimeOnly(12, 0)`                         |
| `#geo(lat,lng)`               | `GeoPoint`                                   | `o => LwnrdbFunctions.Distance(o.Location, point) < 1000`      |
| `#vector(v0,v1,...)`          | `Vector`, or `[LwnrdbVector] double[]/float[]` | `.Nearest(o => o.Embedding, Vector.Of(0.12, 0.44, 0.91), 10)` |

- The server's date-time has no zone. A `DateTime` of `Kind.Unspecified` is stored as written and read values are
  always `Kind.Unspecified`. A `DateTime` of `Kind.Utc` or `Kind.Local`, or a `DateTimeOffset`, is an instant: it is
  refused unless `TemporalZone` is set, and is then stored as that zone's wall-clock time. The server keeps
  nanoseconds; digits past .NET's 100 ns are dropped on read.
- A plain string that looks like a custom value (`#name(...)`) is refused, because the server would read it as one.
- A number must be finite, and an integer must fit in 53 bits: the server stores numbers as doubles, so store a larger
  integer as a string.
- Numbers and date-times are always written with the invariant culture.
- `Nearest` is an approximate, index-backed search; pass `exact: true` (or `.Exact()` on the fluent filter) to scan
  every row and get the true top `k`.

## Queries

`collection.Query()` starts an immutable pipeline; every step returns a new query.

| Step                           | Method                                                                                                                             |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `Filter(...)` with `Field(...).Eq/Ne/Gt/Gte/Lt/Lte/In/NotIn/Contains`, `And/Or/Nor/Xor/Nand`, `Distance`, `Within`, `Nearest`, `Script` |
| `MAP`                          | `Map(MapOps.Set(field, MidOps...), MapOps.Remove(field))`, each optionally `.When(filter)`                                         |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `GroupBy(field)`, `Distinct()`, `Distinct(field)`, `Join(collection, localField, remoteField, asField)`                            |
| `SORT`, `SKIP`, `LIMIT`        | `Sort(field, Order.Asc \| Order.Desc)`, `Skip(n)`, `Limit(n)`                                                                      |
| `REDUCE`                       | `Reduce(script)`, `Reduce(script, initialValue, resultField)`                                                                      |
| `COUNT`                        | `CountAsync()`                                                                                                                     |

Run a query with `ToListAsync()`, `ToDocumentsAsync()` (for rows of another shape, such as `GroupBy`),
`FirstOrDefaultAsync()`, `CountAsync()` or `AnalyzeAsync()`. `DirtyRead()` reads without the collection lock.

## LINQ

`collection.AsQueryable()` returns an `IQueryable<T>` whose provider translates the expression into the same pipeline
the fluent builder makes. Method syntax and query syntax both work. Translation happens on every execution, so a
captured variable is read when the query runs.

| LINQ                                                              | Pipeline                                                         |
| ----------------------------------------------------------------- | ---------------------------------------------------------------- |
| `Where(p)`                                                        | `FILTER`, one per `Where`                                        |
| `==`, `!=`, `<`, `<=`, `>`, `>=`                                  | `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, ...                      |
| `&&`, `\|\|`, `^`                                                 | `AND`, `OR`, `XOR`                                               |
| `!(a && b)`, `!(a \|\| b)`, any other `!(x)`                      | `NAND`, `NOR`, `NOR`                                             |
| `o.Paid`, `!o.Paid`, `o.X == null`, `o.X.HasValue`                | `EQUALS true/false`, `EQUALS null`, `NOT_EQUALS null`            |
| `values.Contains(o.X)`, `!values.Contains(o.X)`                   | `IN`, `NOT_IN`                                                   |
| `o.Tags.Contains(v)`, `o.Name.Contains("an")`                     | `CONTAINS`                                                       |
| `LwnrdbFunctions.Distance(o.Location, p) < m`, `Within`, `Script` | `distance`, `within`, a script filter                            |
| `.Nearest(o => o.Embedding, v, k)`, `.Filter(filter)`             | `nearest`, any fluent filter                                     |
| `OrderBy`, `OrderByDescending`, `Skip`, `Take`                    | `SORT`, `SKIP`, `LIMIT`                                          |
| `Distinct()`, `Select(o => o.Status).Distinct()`                  | `DISTINCT`, `DISTINCT status`                                    |
| `GroupBy(o => o.Status)`                                          | `GROUP_BY`, read as `IGrouping<TKey, T>`                         |
| `GroupJoin(other.AsQueryable(), o => o.CustomerId, c => c.Id, (o, customer) => ...)` | `JOIN`, `asField` named after the second parameter |
| `Count`, `Any`, `All`, `First`, `Single`, `ElementAt`             | `COUNT`, `LIMIT 1`, `LIMIT 2`, `SKIP n LIMIT 1`                  |
| a final `Select(...)`                                             | runs client-side on each row                                     |

The async terminals are `ToListAsync`, `ToArrayAsync`, `FirstAsync`, `FirstOrDefaultAsync`, `SingleAsync`,
`SingleOrDefaultAsync`, `ElementAtAsync`, `CountAsync`, `LongCountAsync`, `AnyAsync`, `AllAsync` and
`AsAsyncEnumerable`, plus `AnalyzeAsync`. The sync operators (`ToList()`, `Count()`, `First()`, `foreach`) work too, but
they block a thread while the request runs; prefer the async ones. `ToQuery()` returns the fluent `Query<T>` an
expression translates to, and `ListenAsync` takes an `IQueryable<T>` directly.

Anything the server cannot run is refused with a `NotSupportedException` naming the expression, rather than evaluated in
memory: `ThenBy` (the server sorts by one field), `Join` (the server's join is a group join: use `GroupJoin`), `Sum`,
`Average`, `Min`, `Max`, `Aggregate`, `Last`, `SelectMany`, a `Where` or `OrderBy` after a `Select`, and any method call
on a member other than `Contains` and `Equals`.

The server's semantics differ from LINQ to Objects in three ways:

- string equality ignores case on every field except `_id`, so `o.Status == "NEW"` matches `"new"`;
- a document that lacks a field matches no comparison on it, so `!(o.Total < 5)` (a `NOR`) also matches documents
  without `total`, and is not the same as `o.Total >= 5`;
- `!` over anything but a comparison, a boolean member or an `IN` becomes `NOR` for that reason.

## Transactions

```csharp
var id = await client.TransactionAsync(async (tx, ct) =>
{
    var accounts = tx.Collection<Account>("bank", "accounts");
    await accounts.SaveAsync(debit, ct);
    await accounts.SaveAsync(credit, ct);
    return tx.Id;
});
```

The body commits when it completes and rolls back when it throws; the body's exception is rethrown unchanged.
`BeginTransactionAsync()` hands the transaction to you instead, and `await using` rolls it back if it is still open at
the end of the block. A transaction keeps one connection until it ends, never retries, and refuses an operation the
server does not allow inside one before sending it. LINQ works inside it through `tx.Collection<T>(...).AsQueryable()`.
The server rolls back a transaction that is idle longer than `transactionIdleTimeoutMs`; its next request fails with
`409-16`.

## Live queries

```csharp
var subscription = await orders.ListenAsync(
    orders.AsQueryable().Where(o => o.Status == "pending"),
    new ListenHandler<Purchase>(async (update, ct) => await RenderAsync(update.Results, ct))
    {
        OnEnded = reason => MarkStaleAsync(reason),
        OnError = error => LogAsync(error),
    });

await subscription.DisposeAsync();
```

The handler receives the full result immediately (`update.Initial`), then again whenever a write changes it. Updates for
the listens of one connection are delivered in order, one at a time: an async `OnUpdate` finishes before the next update
starts. Listens use their own connections, never pooled ones, and spread over more connections when the server's
per-connection limit is reached. `OnEnded` is called when the server ends a listen (for example, its collection was
dropped); `OnError` when a handler threw (which also ends that subscription) or the connection was lost.

## Errors and retries

Every server refusal is an `LwnrdbException` carrying the server's code (`RawCode`, and `Code` for its catalogue
entry), subclassed by range: `AuthenticationException` (401), `ForbiddenException` (403), `NotFoundException` (404),
`ConflictException` (409), `ListenEndedException` (410), `UnavailableException` (503) and `UnknownOutcomeException`
(`500-33`, `500-34`). `ErrorCode` holds every code by name.

The client retries, per `RetryPolicy`:

- a refusal whose code is retryable (`503-2/4/5/9/10/16/17/18/19/20/21`);
- a read (`FIND_BY_ID`, `AGGREGATE`, `LIST_*`, `GET_*`, `SERVER_INFO`) whose connection was lost.

It never retries a write that lost its connection (`ConnectionLostException`: the outcome is unknown), an
`UnknownOutcomeException`, or anything inside a transaction. A request with no reply within `RequestTimeout` closes its
connection and fails with `RequestTimeoutException`.

## TLS

```csharp
var client = await LwnrdbClient.ConnectAsync(new ClientSettings
{
    Hosts = [new ServerAddress("db.example.com", 8989)],
    Tls = new TlsSettings { TrustedCertificates = TlsSettings.LoadPkcs12("lwnrdb.p12", keystorePassword) },
});
```

`new TlsSettings()` uses the operating system's trust store, `TrustedCertificates` trusts only the certificates you give
it (`TlsSettings.LoadPem` or `TlsSettings.LoadPkcs12`, so the server's own PKCS12 keystore works as is), `ServerName`
overrides the name checked against the certificate, and `Insecure = true` trusts any certificate (development only; it
writes a warning through `Trace`).

## API reference

Every public type and member is documented in `Lwnrdb.Client.xml`, which the package ships next to the assembly, so
IDEs show it on hover. The text lives in [`apidocs/`](apidocs), one Markdown file per public type with one
`## Member(ParameterTypes)` section per public member, because the sources carry no comments. The build runs
`build-tools/Lwnrdb.DocGen`, which writes the XML file and fails when a public member has no section or a section names
nothing.

## Building

```bash
dotnet build Lwnrdb.slnx -c Release
dotnet test tests/Lwnrdb.Client.Tests -c Release /p:CollectCoverage=true /p:Threshold=95 /p:ThresholdType=line
dotnet format Lwnrdb.slnx --verify-no-changes
dotnet pack src/Lwnrdb.Client -c Release -o artifacts && dotnet run --project consumers/Console
mvn package -DskipTests        # at the repository root: the server jar the integration tests run
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar dotnet test tests/Lwnrdb.Client.IntegrationTests -c Release
```

The integration tests start their own servers on free ports and need `java` on the `PATH`.

## Releasing

Push a `dotnet-client-vX.Y.Z` tag. `.github/workflows/release-dotnet-client.yml` runs the unit and integration tests
against a server built from the same commit, packs the version from the tag, pushes the `.nupkg` and its `.snupkg`
symbols to nuget.org and creates a GitHub release (a version with a suffix is a pre-release).

It publishes through NuGet trusted publishing, so no API key is stored. Once, on nuget.org: add a trusted publishing
policy for the repository owner, the `lwnrdb` repository and the workflow file `release-dotnet-client.yml`. Then add one
repository secret, `NUGET_USER`: the nuget.org account name (not an e-mail address) that owns the policy. The workflow
exchanges its GitHub OIDC token for a short-lived API key at publish time.
