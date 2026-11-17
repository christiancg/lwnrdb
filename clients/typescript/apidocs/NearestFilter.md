# NearestFilter

A vector top-k filter. Approximate by default: the index proposes candidates, so a true
neighbour can be missed; call `exact()` to scan every row.

## kind

Always `"nearest"`.

## field

The vector field path.

## query

The vector to compare with.

## k

How many neighbours to keep.

## isExact

Whether the filter scans every row.

## exact

@returns the same filter, answered by a full scan
