# MapOperation

One `MAP` operation: set a field from a computation, or remove it, optionally only on rows a filter matches.

## field

The field the operation writes.

## operation

The computation, or `undefined` for a removal.

## condition

The filter that limits the operation, or `undefined`.

## when

@param condition the filter rows must match
@returns the same operation, applied only to matching rows

## removes

Whether the operation removes its field.
