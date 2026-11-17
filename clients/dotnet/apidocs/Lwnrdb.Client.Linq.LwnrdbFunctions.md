Server-side functions for LINQ predicates. They have a meaning only inside a `Where` of a query from `AsQueryable()`, where the provider translates them into geo and script filters.

Calling one outside a query throws `InvalidOperationException`.

## Distance(GeoPoint, GeoPoint)
The great-circle distance in metres between a `#geo` member and a point. Compare it with a number of metres using `==`, `<`, `<=`, `>` or `>=`; `!=` is refused.

@param field the `#geo` member of the document
@param target the point to measure from
@returns the distance in metres; never returns outside a query
@exception System.InvalidOperationException when called outside a query

## Script(string)
Matches documents for which a script returns true. The source is a module whose default export is `(doc) => boolean`. It can be combined with `&&` and `||`, and is refused in a listen.

@param source the script source, known before the query runs
@returns true when the script accepts the document; never returns outside a query
@exception System.InvalidOperationException when called outside a query

## Within(GeoPoint, GeoPoint[])
Matches documents whose `#geo` member lies inside a polygon.

@param field the `#geo` member of the document
@param polygon the polygon's vertices, at least three
@returns true when the point is inside the polygon; never returns outside a query
@exception System.InvalidOperationException when called outside a query
