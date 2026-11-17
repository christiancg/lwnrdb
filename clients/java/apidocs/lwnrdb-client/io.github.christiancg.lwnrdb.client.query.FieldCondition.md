The conditions on one field. Strings compare case-insensitively, except `_id`; custom values compare
semantically. A value may be a number, string, boolean, `null`, `Document`, list, or one of the custom types.

## eq(Object)

@param value the value; `null` matches only fields holding `null`
@return the filter

## ne(Object)

@param value the value
@return the filter

## gt(Object)

@param value the bound
@return the filter

## gte(Object)

@param value the bound
@return the filter

## lt(Object)

@param value the bound
@return the filter

## lte(Object)

@param value the bound
@return the filter

## in(Object...)

@param values the allowed values
@return the filter

## in(Collection)

@param values the allowed values
@return the filter

## notIn(Object...)

@param values the excluded values
@return the filter

## notIn(Collection)

@param values the excluded values
@return the filter

## contains(Object)

@param value a substring of a string field, or an element of an array field
@return the filter
