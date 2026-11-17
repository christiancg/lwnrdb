A LINQ query over one LWNRDB collection. Obtained from `Collection<T>.AsQueryable()` or `TransactionCollection<T>.AsQueryable()`.

It implements `IOrderedQueryable<T>` and `IAsyncEnumerable<T>`. The expression is translated into the same pipeline steps as the fluent `Query<T>`, on every execution and never when the query is built, so a captured variable is read when the query runs. A query the server cannot run throws `NotSupportedException` at that moment, naming the part it refused. Prefer the async terminals of `LwnrdbQueryableExtensions` (`ToListAsync`, `CountAsync`, `FirstOrDefaultAsync`, ...); the synchronous ones (`ToList()`, `Count()`, `First()`, `foreach`) work but block the calling thread.

Supported operators and their steps: `Where` becomes a `FILTER` step per call, with `==`, `!=`, `<`, `<=`, `>` and `>=` between a stored member and a value, `&&`, `||` and `^` as `AND`, `OR` and `XOR`, `!` over a conjunction as `NAND` or `NOR`, a boolean member as an equality with `true`, comparisons with `null`, `values.Contains(o.X)` over a captured collection as `IN` (`NOT_IN` when negated), `o.Tags.Contains(v)` and `o.Name.Contains("an")` (ordinal) as `CONTAINS`, and `LwnrdbFunctions.Distance`, `Within` and `Script`. `OrderBy` and `OrderByDescending` become `SORT`, `Skip` becomes `SKIP`, `Take` becomes `LIMIT` (`Take(0)` answers empty without a request), `Distinct()` and `Select(o => o.Member).Distinct()` become `DISTINCT`, `GroupBy(key)` becomes `GROUP_BY`, `GroupJoin` becomes `JOIN`, and `Count`, `LongCount`, `Any`, `All`, `First`, `FirstOrDefault`, `Single`, `SingleOrDefault` and `ElementAt` end the query with `COUNT`, `LIMIT` or `SKIP` and `LIMIT` steps. `Nearest`, `Filter` and `DirtyRead` of `LwnrdbQueryableExtensions` add a nearest-neighbour filter, a fluent filter and the dirty-read flag.

Differences from LINQ to Objects: string `==`, `!=`, `Equals` with `OrdinalIgnoreCase` and `Contains` compare case-insensitively on the server, except on `_id`, which is compared exactly. A document that is missing a field never matches a comparison on it, so `!(x < 5)` is not the same as `x >= 5`; the client translates `!` over anything but a plain equality to `NOR`. Member names are mapped through the `JsonSerializerOptions` of the client, so `[JsonPropertyName]`, naming policies and the `Id` to `_id` alias apply, and a `Where` operand is encoded like a stored value (an enum stored as a string is compared as a string). Only comparisons of a stored member with a value run on the server; two members compared with each other do not.

`Select` runs on the client over the decoded rows, so it must be the last shaping operator and cannot filter. `ThenBy` and `ThenByDescending` are refused, because the server sorts by one field; `Join` is refused, use `GroupJoin`, whose result selector's second parameter name becomes the joined field (`asField`) and whose inner source must be another collection of the same database with no operators. `Sum`, `Average`, `Min`, `Max`, `Aggregate`, `Last`, `Reverse`, `SelectMany`, `Union`, `Concat` and `Zip` are refused: aggregate in memory after `ToListAsync`, or use `Query<T>.Reduce`. `ToQuery()` returns the equivalent fluent `Query<T>`.

@typeparam T the type of the elements

## GetAsyncEnumerator(CancellationToken)
Translates and runs the query, then streams the decoded rows. The whole result is read in one request first.

@param cancellationToken cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the enumerator
@exception System.NotSupportedException when the query cannot be translated

## GetEnumerator()
Translates and runs the query, blocking the calling thread until the result arrives. Prefer `ToListAsync` or `await foreach`.

@returns the enumerator
@exception System.NotSupportedException when the query cannot be translated

## ElementType
The element type, `T`.

## Expression
The expression tree of the query.

## Provider
The provider that translates and runs the query.
