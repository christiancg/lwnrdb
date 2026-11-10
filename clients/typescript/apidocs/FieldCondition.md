# FieldCondition

A field path waiting for a comparison. Obtained from `field(name)`. String values compare
without case, except on `_id`.

## field

The field path.

## eq

@param value the value to equal; `null` matches null and missing fields
@returns an `EQUALS` filter

## ne

@param value the value to differ from
@returns a `NOT_EQUALS` filter

## gt

@param value the exclusive lower bound
@returns a `GREATER_THAN` filter

## gte

@param value the inclusive lower bound
@returns a `GREATER_THAN_EQUALS` filter

## lt

@param value the exclusive upper bound
@returns a `SMALLER_THAN` filter

## lte

@param value the inclusive upper bound
@returns a `SMALLER_THAN_EQUALS` filter

## in

@param values the accepted values, spread as arguments
@returns an `IN` filter

## notIn

@param values the refused values, spread as arguments
@returns a `NOT_IN` filter

## contains

@param value the substring, or the element of an array field
@returns a `CONTAINS` filter
