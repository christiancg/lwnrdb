# LWNRDB .NET client

The .NET client for [LWNRDB](https://github.com/christiancg/lwnrdb). It keeps a pool of authenticated connections and
gives you a fluent query builder, a LINQ provider, transactions, live queries and the server's custom value types. It
needs .NET 10 or newer and has no NuGet dependencies.

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

A collection has to exist before you write to it. Besides `SaveAsync` and `FindByIdAsync`, a collection has
`InsertAsync`, `SaveAllAsync`, `DeleteAsync`, `UpdateWhereAsync` and `DeleteWhereAsync`. The pool size is how many
requests run at once, and every async method takes a `CancellationToken`.

## Settings

The ones you usually set are `Hosts` (tried in turn), `Credentials`, `Tls` and `PoolSize` (8 by default). Timeouts,
the retry policy and the rest are in the API reference.

## Typed collections

`Collection<T>(name)` maps documents through `System.Text.Json`, camelCase by default. A property named `Id` is stored
as `_id` and must be a `string`. To use your own options, including a source-generated context, set
`ClientSettings.Json`:

```csharp
var settings = new ClientSettings { Json = new JsonSerializerOptions { TypeInfoResolver = ShopJsonContext.Default } };
```

`Collection(name)` without a type argument works with `Document`, an immutable JSON object with typed getters.

## Custom value types

The server has four value types of its own. The client maps them both ways.

| Server value                  | .NET type                                      | Example                                                       |
| ----------------------------- | ---------------------------------------------- | ------------------------------------------------------------- |
| `#datetime(2024-01-01T10:00)` | `DateTime`                                     | `Field("placedAt").Gte(new DateTime(2024, 1, 1))`             |
| `#time(09:30)`                | `TimeOnly`                                     | `o => o.OpensAt < new TimeOnly(12, 0)`                        |
| `#geo(lat,lng)`               | `GeoPoint`                                     | `o => LwnrdbFunctions.Distance(o.Location, point) < 1000`     |
| `#vector(v0,v1,...)`          | `Vector`, or `[LwnrdbVector] double[]/float[]` | `.Nearest(o => o.Embedding, Vector.Of(0.12, 0.44, 0.91), 10)` |

The server's date-time has no zone, so a `DateTime` of `Kind.Utc` or `Kind.Local`, or a `DateTimeOffset`, is refused
unless you set `TemporalZone`. The server stores numbers as doubles, so keep integers past 53 bits in strings.
`Nearest` is approximate. Pass `exact: true` to get the true top `k`.

## Queries

`collection.Query()` starts a pipeline. Queries are immutable, so every step returns a new one.

| Step                           | Method                                                                                                                                  |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `FILTER`                       | `Filter(...)` with `Field(...).Eq/Ne/Gt/Gte/Lt/Lte/In/NotIn/Contains`, `And/Or/Nor/Xor/Nand`, `Distance`, `Within`, `Nearest`, `Script` |
| `MAP`                          | `Map(MapOps.Set(field, MidOps...), MapOps.Remove(field))`, each optionally `.When(filter)`                                              |
| `GROUP_BY`, `DISTINCT`, `JOIN` | `GroupBy(field)`, `Distinct()`, `Distinct(field)`, `Join(collection, localField, remoteField, asField)`                                 |
| `SORT`, `SKIP`, `LIMIT`        | `Sort(field, Order.Asc \| Order.Desc)`, `Skip(n)`, `Limit(n)`                                                                           |
| `REDUCE`                       | `Reduce(script)`, `Reduce(script, initialValue, resultField)`                                                                           |
| `COUNT`                        | `CountAsync()`                                                                                                                          |

Run it with `ToListAsync()`, `ToDocumentsAsync()`, `FirstOrDefaultAsync()`, `CountAsync()` or `AnalyzeAsync()`.

## LINQ

`collection.AsQueryable()` returns an `IQueryable<T>` that is translated into the same pipeline the fluent builder
makes. `Where`, `OrderBy`, `Skip`, `Take`, `Distinct`, `GroupBy`, `GroupJoin` and `Count`/`Any`/`First` translate to
server steps. Anything the server can't run, such as `ThenBy` or `Sum`, throws a `NotSupportedException` instead of
running in memory.

```csharp
string[] statuses = ["new", "paid"];
var recent = await orders.AsQueryable()
    .Where(o => statuses.Contains(o.Status) && o.PlacedAt >= since)
    .CountAsync();
```

String equality ignores case on every field except `_id`, unlike LINQ to Objects.

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

The body commits when it completes and rolls back when it throws. A transaction holds one connection, its writes lock
their collections until the commit, and the server rolls it back after `transactionIdleTimeoutMs` idle.

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

The handler gets the whole result right away, then again every time a write changes it, one update at a time. Live
queries use their own connections, not the pool.

## Errors and retries

Every refusal from the server is an `LwnrdbException` with the server's error code. There is a subclass per range:
`AuthenticationException` (401), `ForbiddenException` (403), `NotFoundException` (404), `ConflictException` (409),
`ListenEndedException` (410), `UnavailableException` (503) and `UnknownOutcomeException` (`500-33`, `500-34`).

The client retries [retryable](../../docs/protocol.md#error-codes) codes and reads that lost their connection. It
never retries a write that lost its connection (`ConnectionLostException`), an unknown outcome, or anything inside a
transaction.

## TLS

```csharp
var client = await LwnrdbClient.ConnectAsync(new ClientSettings
{
    Hosts = [new ServerAddress("db.example.com", 8989)],
    Tls = new TlsSettings { TrustedCertificates = TlsSettings.LoadPkcs12("lwnrdb.p12", keystorePassword) },
});
```

`new TlsSettings()` uses the operating system's trust store, and `Insecure = true` trusts any certificate, only for
development.

## API reference

The API docs are in [`apidocs/`](apidocs) and in `Lwnrdb.Client.xml`, which ships in the package, so your IDE shows
them on hover.

## Building

```bash
dotnet build Lwnrdb.slnx -c Release
dotnet test tests/Lwnrdb.Client.Tests -c Release /p:CollectCoverage=true /p:Threshold=95 /p:ThresholdType=line
dotnet format Lwnrdb.slnx --verify-no-changes
dotnet pack src/Lwnrdb.Client -c Release -o artifacts && dotnet run --project consumers/Console
mvn package -DskipTests        # at the repository root, the server jar the integration tests use
LWNRDB_JAR=$PWD/../../target/lwnrdb.jar dotnet test tests/Lwnrdb.Client.IntegrationTests -c Release
```

## Releasing

Push a `dotnet-client-vX.Y.Z` tag. The release workflow tests the client and publishes it to nuget.org.
