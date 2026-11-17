Builds the operations of a `Map` step.

## Remove(string)
Removes a field from every row.

@param field the field name or dotted path
@returns the operation

## Set(string, MidOperation)
Sets a field on every row to the result of an operation.

@param field the field name or dotted path
@param operation the computation; build it with `MidOps`
@returns the operation; add `When` to apply it only to some rows
