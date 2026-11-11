Builds the conditions of a `FILTER` step, and the conditions inside `Map` operations.

Start a field comparison with `Field`, or combine conditions with `And`, `Or`, `Nor`, `Xor` and `Nand`. String comparisons are case-insensitive on the server, except on `_id`, which is compared exactly. A document that lacks the field is excluded from every comparison.

## And(Filter[])
Matches documents that satisfy every operand.

@param operands the conditions, at least one
@returns the combined filter
@exception System.ArgumentException when no operand is given

## Distance(string, GeoPoint, GeoDistanceComparator, double)
Compares the great-circle distance between a `#geo` field and a point with a limit.

@param field the `#geo` field
@param target the point to measure from
@param comparator how the distance is compared with the limit
@param metres the limit in metres, finite and not negative
@returns the filter
@exception System.ArgumentOutOfRangeException when the limit is negative or not finite

## Field(string)
Starts a comparison on a field.

@param name the field name or dotted path
@returns the builder of the comparison

## Nand(Filter[])
Matches documents that do not satisfy all of the operands.

@param operands the conditions, at least one
@returns the combined filter

## Nearest(string, Vector, int)
Keeps the `k` documents whose `#vector` field is most similar (cosine) to a query vector. The search is approximate unless `Exact()` is called on the result.

@param field the `#vector` field
@param query the vector to search for
@param k the number of neighbours, at least 1
@returns the filter
@exception System.ArgumentOutOfRangeException when `k` is not positive

## Nor(Filter[])
Matches documents that satisfy none of the operands.

@param operands the conditions, at least one
@returns the combined filter

## Or(Filter[])
Matches documents that satisfy at least one operand.

@param operands the conditions, at least one
@returns the combined filter

## Script(string)
Matches documents for which a script returns true. Script filters are refused in a listen.

@param source the script source
@returns the filter

## Within(string, GeoPoint[])
Matches documents whose `#geo` field lies inside a polygon.

@param field the `#geo` field
@param polygon the polygon's vertices, at least three
@returns the filter
@exception System.ArgumentException when fewer than three points are given

## Xor(Filter[])
Matches documents that satisfy exactly one operand.

@param operands the conditions, at least one
@returns the combined filter
