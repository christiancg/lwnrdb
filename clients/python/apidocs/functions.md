# functions

The filter functions and helpers exported at the package's top level.

## field

Starts a condition on a field; the dotted path reaches nested fields.

```python
field("status").eq("active")
field("total") > 100
```

## and_

Matches documents that match every operand. `a & b` is the same filter.

## or_

Matches documents that match at least one operand. `a | b` is the same filter.

## nor

Matches documents that match no operand. `~a` is `nor(a)`.

## xor

Matches documents that match exactly one operand. `a ^ b` is `xor(a, b)`; `a ^ b ^ c` nests, so write
`xor(a, b, c)` for exactly one of three.

## nand

Matches documents that do not match every operand.

## distance

Compares the distance in metres between a geo field and a point.

Args:
    name: the geo field
    target: the point
    comparator: how the distance compares with `metres`
    metres: a finite, non-negative distance

## within

Matches documents whose geo field lies inside a polygon of at least three points.

## nearest

Ranks documents by vector similarity and keeps the `k` nearest. Approximate unless `.exact()` is called.

Args:
    name: the vector field
    query: the vector to compare with
    k: how many to keep, at least 1

## script

A filter that runs a SimpleJS predicate on each document; needs the script permission.

## collection_key

Returns the key `database|collection` that collection permissions use.
