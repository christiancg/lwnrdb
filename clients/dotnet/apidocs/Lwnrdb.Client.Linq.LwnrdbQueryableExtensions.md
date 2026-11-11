Async terminals and LWNRDB operators for queries from `Collection<T>.AsQueryable()`.

The async terminals translate the expression as `LwnrdbQueryable<T>` describes and run it without blocking. Each takes a `CancellationToken` that cancels the wait; a request already sent closes its connection, since its outcome is unknown. They throw `InvalidOperationException` when the source is not a query from a LWNRDB collection, and `NotSupportedException` when the expression cannot be translated.

## AllAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Tells whether every document satisfies a predicate. It is sent as a filter on the negation, limited to one row, and is true when nothing comes back.

@typeparam T the element type
@param source the query
@param predicate the condition every document must satisfy
@param ct cancels the wait
@returns true when every document satisfies the predicate

## AnalyzeAsync<T>(IQueryable<T>, CancellationToken)
Runs the query with `analyze` set and returns the rows with the server's analysis of how it ran.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the rows as documents and the analysis
@exception System.NotSupportedException when the query cannot be translated

## AnyAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Tells whether any document satisfies a predicate.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns true when at least one document satisfies the predicate

## AnyAsync<T>(IQueryable<T>, CancellationToken)
Tells whether the query returns any document.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns true when the query returns at least one document

## AsAsyncEnumerable<T>(IQueryable<T>)
Returns the query as an `IAsyncEnumerable<T>`, for `await foreach`.

@typeparam T the element type
@param source a query from `AsQueryable()`
@returns the query as an async sequence
@exception System.InvalidOperationException when the source is not a LWNRDB query

## CountAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Counts the documents that satisfy a predicate, with a `COUNT` step.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the number of documents

## CountAsync<T>(IQueryable<T>, CancellationToken)
Counts the documents the query returns, with a `COUNT` step.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the number of documents

## DirtyRead<T>(IQueryable<T>)
Reads without waiting for the collection's read lock. The read may see a write that is still being applied.

@typeparam T the element type
@param source the query
@returns the new query

## ElementAtAsync<T>(IQueryable<T>, int, CancellationToken)
Returns the document at an index, with a `SKIP` and a `LIMIT 1`.

@typeparam T the element type
@param source the query
@param index the zero-based position
@param ct cancels the wait
@returns the document
@exception System.ArgumentOutOfRangeException when there is no document at the index

## Filter<T>(IQueryable<T>, Filter)
Adds a `FILTER` step built with the fluent `Filters`, so both styles can be mixed in one query.

@typeparam T the element type
@param source the query
@param filter the condition
@returns the new query

## FirstAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Returns the first document that satisfies a predicate, with a `LIMIT 1`.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the first document
@exception System.InvalidOperationException when no document matches

## FirstAsync<T>(IQueryable<T>, CancellationToken)
Returns the first document, with a `LIMIT 1`.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the first document
@exception System.InvalidOperationException when the query returns nothing

## FirstOrDefaultAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Returns the first document that satisfies a predicate, with a `LIMIT 1`.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the first document, or the default value of `T` when none matches

## FirstOrDefaultAsync<T>(IQueryable<T>, CancellationToken)
Returns the first document, with a `LIMIT 1`.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the first document, or the default value of `T` when the query returns nothing

## LongCountAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Counts the documents that satisfy a predicate, with a `COUNT` step.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the number of documents

## LongCountAsync<T>(IQueryable<T>, CancellationToken)
Counts the documents the query returns, with a `COUNT` step.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the number of documents

## Nearest<T>(IQueryable<T>, Expression<Func<T, object>>, Vector, int, bool)
Keeps the `k` documents whose vector member is most similar (cosine) to a query vector. The search is approximate unless `exact` is true.

@typeparam T the element type
@param source the query
@param member the vector member, such as `o => o.Embedding`: a `Vector` or a `[LwnrdbVector]` array
@param query the vector to search for
@param k the number of neighbours, at least 1
@param exact true to scan instead of using the approximate index
@returns the new query

## SingleAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Returns the only document that satisfies a predicate, with a `LIMIT 2`.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the document
@exception System.InvalidOperationException when no document or more than one matches

## SingleAsync<T>(IQueryable<T>, CancellationToken)
Returns the only document, with a `LIMIT 2`.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the document
@exception System.InvalidOperationException when the query returns no document or more than one

## SingleOrDefaultAsync<T>(IQueryable<T>, Expression<Func<T, bool>>, CancellationToken)
Returns the only document that satisfies a predicate, with a `LIMIT 2`.

@typeparam T the element type
@param source the query
@param predicate the condition
@param ct cancels the wait
@returns the document, or the default value of `T` when none matches
@exception System.InvalidOperationException when more than one document matches

## SingleOrDefaultAsync<T>(IQueryable<T>, CancellationToken)
Returns the only document, with a `LIMIT 2`.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the document, or the default value of `T` when the query returns nothing
@exception System.InvalidOperationException when the query returns more than one document

## ToArrayAsync<T>(IQueryable<T>, CancellationToken)
Runs the query and returns the rows as an array.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the rows; empty when nothing matches

## ToListAsync<T>(IQueryable<T>, CancellationToken)
Runs the query and returns the rows as a list.

@typeparam T the element type
@param source the query
@param ct cancels the wait
@returns the rows; empty when nothing matches

## ToQuery<T>(IQueryable<T>)
Returns the fluent `Query<T>` the expression translates to, which `ListenAsync` also accepts.

@typeparam T the element type
@param source the query
@returns the equivalent fluent query
@exception System.NotSupportedException when the query does not return the collection's documents: it ends in `Select`, `GroupBy`, `GroupJoin`, a `Distinct` of a member, `Take(0)` or a terminal operator
