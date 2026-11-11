# FieldCondition

A condition under construction on one field, returned by `field(name)`. `<`, `<=`, `>` and `>=` are aliases of
`lt`, `lte`, `gt` and `gte`; equality is `eq`/`ne` only.

## field

The field's path.

## eq

Matches the documents whose field equals the value; `None` matches a missing or null field.

## ne

Matches the documents whose field differs from the value.

## gt

Matches the documents whose field is greater than the value.

## gte

Matches the documents whose field is greater than or equal to the value.

## lt

Matches the documents whose field is smaller than the value.

## lte

Matches the documents whose field is smaller than or equal to the value.

## in_

Matches the documents whose field equals one of the values.

## not_in

Matches the documents whose field equals none of the values.

## contains

Matches the documents whose text field contains the value.
