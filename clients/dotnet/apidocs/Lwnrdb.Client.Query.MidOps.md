Builds the computations of `Map` operations.

An operand of an array operation is a number or other value, the name of a field passed as a string, or a `Literal` for text that is not a field name.

## Abs(string)
The absolute value of a numeric field.

@param field the field name
@returns the computation

## Avg(object[])
The average of the operands.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Cast(string, CastType)
Converts a field to a number, string or boolean.

@param field the field name
@param type the target type; `CastType.JsonCustom` is refused, use the `CustomType` overload
@returns the computation; a value that cannot be converted becomes null
@exception System.ArgumentException when the type is `JsonCustom` or not a defined value

## Cast(string, CustomType)
Converts a string field to a custom value type; a value that cannot be parsed becomes null.

@param field the field name
@param type the custom type
@returns the computation

## Concat(object[])
The concatenation of the operands as text.

@param operands values, field names or `Literal` values, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Divide(object[])
The first operand divided by the others.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Literal(string)
Marks text as a literal operand instead of a field name.

@param text the text; not empty
@returns the literal

## Max(object[])
The largest of the operands.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Min(object[])
The smallest of the operands.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Multiply(object[])
The product of the operands.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Pow(object[])
The first operand raised to the second.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Root(object[])
The root of the first operand, of the degree given by the second.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Script(string)
The value a script returns for the row.

@param source the script source
@returns the computation

## Size(string)
The size of an array, object or string field.

@param field the field name
@returns the computation

## Subs(object[])
The first operand minus the others.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given

## Sum(object[])
The sum of the operands.

@param operands numbers or field names, at least one
@returns the computation
@exception System.ArgumentException when no operand is given
