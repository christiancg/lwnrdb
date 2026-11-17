Builds the value of a `map` field. A `String` operand is a field path; use `literal` for text, and
numbers for constants.

## avg(Object...)

@param operands field paths and numbers
@return the average

## sum(Object...)

@param operands field paths and numbers
@return the sum

## subs(Object...)

@param operands field paths and numbers
@return the first operand minus the others

## max(Object...)

@param operands field paths and numbers
@return the largest

## min(Object...)

@param operands field paths and numbers
@return the smallest

## multiply(Object...)

@param operands field paths and numbers
@return the product

## divide(Object...)

@param operands field paths and numbers
@return the first operand divided by the others

## pow(Object...)

@param operands field paths and numbers
@return the first operand raised to the second

## root(Object...)

@param operands field paths and numbers
@return the second operand's root of the first

## concat(Object...)

@param operands field paths, `literal` text and numbers
@return the joined string

## literal(String)

@param text text used as is, not as a field path
@return the literal operand

## abs(String)

@param field a numeric field
@return its absolute value

## size(String)

@param field an array, object or string field
@return its size

## cast(String, CastType)

@param field the source field
@param type the target type; a value that cannot convert becomes `null`
@return the converted value

## cast(String, CustomType)

@param field a string field
@param customType the custom type to parse it as
@return the custom value, or `null`

## script(String)

@param source a SimpleJS module whose default export is `(doc) => value`
@return the computed value
