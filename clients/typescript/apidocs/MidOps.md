# MidOps

Builders for the computations `MapOps.set` takes.

## avg

Computes the average of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## sum

Computes the sum of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## subs

Computes the first operand minus the others, the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## max

Computes the largest of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## min

Computes the smallest of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## multiply

Computes the product of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## divide

Computes the first operand divided by the others, the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## pow

Computes the first operand raised to the others, the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## root

Computes the root of the first operand by the others, the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## concat

Computes the concatenation of the operands.

@param operands field names, `literal(...)` texts and numbers; at least one
@returns the computation

## literal

@param text text used as is rather than read as a field name
@returns the literal

## abs

@param field the field path
@returns its absolute value

## size

@param field the field path
@returns the length of its array or string

## cast

@param field the field path
@param type a `CastType` other than `JSON_CUSTOM`, or a `CustomType`
@returns the conversion

## script

@param source a SimpleJS script whose default export receives the row
@returns the computation
