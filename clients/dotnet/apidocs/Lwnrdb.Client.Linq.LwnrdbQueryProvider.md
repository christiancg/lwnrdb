The `IQueryProvider` behind `LwnrdbQueryable<T>`. It builds composed queries and executes them; applications use it through the `IQueryable` operators.

Its `Execute` methods block until the reply arrives, which is how the synchronous terminals (`Count()`, `First()`, `ToList()`) work. Use the async terminals of `LwnrdbQueryableExtensions` instead where you can.

## CreateQuery(Expression)
Creates a query from an expression tree.

@param expression the expression, of type `IQueryable<TElement>`
@returns the query

## CreateQuery<TElement>(Expression)
Creates a query from an expression tree.

@typeparam TElement the element type
@param expression the expression tree
@returns the query

## Execute(Expression)
Translates and runs an expression, blocking the calling thread.

@param expression the expression tree, ending in a terminal operator or a sequence
@returns the result
@exception System.NotSupportedException when the expression cannot be translated

## Execute<TResult>(Expression)
Translates and runs an expression, blocking the calling thread.

@typeparam TResult the type of the result
@param expression the expression tree, ending in a terminal operator or a sequence
@returns the result
@exception System.NotSupportedException when the expression cannot be translated
@exception System.InvalidOperationException when `First` or `Single` finds no row, or `Single` finds more than one
