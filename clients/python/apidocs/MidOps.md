# MidOps

Builds the operations `MapOps.set` assigns. A text operand names a field; wrap it in `MidOps.literal` to use the
text itself.

## avg

The average of the operands.

## sum

The sum of the operands.

## subs

The first operand minus the others.

## max

The largest operand.

## min

The smallest operand.

## multiply

The product of the operands.

## divide

The first operand divided by the others.

## pow

The first operand raised to the others.

## root

The root of the first operand by the others.

## concat

The operands joined as text.

## literal

A text operand taken literally.

## abs

The absolute value of a field.

## size

The length of a field's text, list or object.

## cast

Converts a field to a `CastType` (other than `JSON_CUSTOM`) or to a `CustomType`.

## script

Computes the value with a SimpleJS script.
