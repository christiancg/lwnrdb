The vector `nearest` operator.

## NearestFilter(String, Vector, int, boolean)

@param field the vector field
@param query the query vector
@param k how many rows to keep
@param exact `true` to scan every row instead of searching the index

## exact()

@return the same filter answered by a full scan, which never misses a neighbour

## field()

@return the vector field

## query()

@return the query vector

## k()

@return how many rows are kept

## isExact()

@return `true` when answered by a full scan
