A top-k filter on a `#vector` field.

The search is approximate by default: a true neighbour can be missed. `Exact()` makes the server scan instead.

@param Field the `#vector` field
@param Query the vector to search for
@param K the number of neighbours
@param IsExact true to scan instead of using the approximate index

## NearestFilter(string, Vector, int, bool)
Creates a filter.

@param Field the `#vector` field
@param Query the vector to search for
@param K the number of neighbours
@param IsExact true to scan instead of using the approximate index

## Exact()
Returns a copy that scans every document instead of using the approximate index.

@returns the exact filter

## Field
The `#vector` field.

## IsExact
Whether the search scans instead of using the approximate index.

## K
The number of neighbours.

## Query
The vector to search for.
