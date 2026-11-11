A comparison being built on one field. Obtained from `Filters.Field`.

Values are encoded as in `Document.Of`, so custom value types and collections are accepted. A `DateTime` must have kind `Unspecified` here.

## Contains(object)
Matches an array field that contains the value, or a string field that contains the text.

@param value the element or substring
@returns the filter

## Eq(object)
Matches a field equal to the value.

@param value the value; null matches a field holding null
@returns the filter

## Gt(object)
Matches a field greater than the value.

@param value the value
@returns the filter

## Gte(object)
Matches a field greater than or equal to the value.

@param value the value
@returns the filter

## In(object[])
Matches a field equal to any of the values.

@param values the values
@returns the filter

## Lt(object)
Matches a field smaller than the value.

@param value the value
@returns the filter

## Lte(object)
Matches a field smaller than or equal to the value.

@param value the value
@returns the filter

## Ne(object)
Matches a field that is present and not equal to the value.

@param value the value
@returns the filter

## NotIn(object[])
Matches a field equal to none of the values.

@param values the values
@returns the filter

## Field
The field the comparison applies to.
