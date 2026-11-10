Builds filters. Import its methods statically:

```java
import static io.github.christiancg.lwnrdb.client.query.Filters.*;

orders.query().filter(or(field("status").eq("new"), field("total").gte(100)));
```

## field(String)

@param field a field name, dotted for a nested field
@return the conditions on that field

## and(Filter...)

@param operands at least one filter
@return a filter matching rows all operands match

## or(Filter...)

@param operands at least one filter
@return a filter matching rows any operand matches

## nor(Filter...)

@param operands at least one filter
@return a filter matching rows no operand matches

## xor(Filter...)

@param operands at least one filter
@return a filter matching rows exactly one operand matches

## nand(Filter...)

@param operands at least one filter
@return a filter matching rows not every operand matches

## distance(String, GeoPoint, GeoDistanceComparator, double)

Compares the great-circle distance between a geo field and a point.

@param field the geo field
@param target the point
@param comparator how the distance compares to `metres`
@param metres the threshold
@return the filter

## within(String, GeoPoint...)

@param field the geo field
@param polygon at least three points
@return a filter matching points inside the polygon

## within(String, List)

@param field the geo field
@param polygon at least three points
@return a filter matching points inside the polygon

## nearest(String, Vector, int)

Keeps the `k` rows whose vector is most similar (cosine) to the query vector. Approximate unless `.exact()`
is called: an index-backed search may miss a true neighbour.

@param field the vector field
@param query the query vector
@param k how many rows to keep
@return the filter

## script(String)

@param source a SimpleJS module whose default export is `(doc) => boolean`
@return the filter
