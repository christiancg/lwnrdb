One operation of a `Map` step: sets a field to a computed value, or removes it.

@param Field the field to set or remove
@param Operation the computation, or null to remove the field
@param Condition the condition under which the operation applies, or null for every row

## MapOperation(string, MidOperation, Filter)
Creates an operation.

@param Field the field to set or remove
@param Operation the computation, or null to remove the field
@param Condition the condition under which the operation applies, or null for every row

## When(Filter)
Returns a copy that applies only to the rows that satisfy a condition.

@param condition the condition
@returns the conditional operation

## Condition
The condition under which the operation applies, or null for every row.

## Field
The field to set or remove.

## Operation
The computation, or null when the operation removes the field.

## Removes
Whether the operation removes the field.
