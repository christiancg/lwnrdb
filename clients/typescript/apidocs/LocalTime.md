# LocalTime

A time of day without a zone, the server's `#time(...)` value. Immutable.

## hour

The hour, 0-23.

## minute

The minute, 0-59.

## second

The second, 0-59.

## nanosecond

The nanosecond, 0-999999999.

## of

@param hour 0-23
@param minute 0-59
@param second optional, 0 by default
@param nanosecond optional, 0 by default
@returns the time

## parse

@param text text such as `09:30` or `09:30:15.250`
@returns the time

## compareTo

@param other another time
@returns -1, 0 or 1

## equals

@param other any value
@returns whether it is a `LocalTime` with the same fields

## toString

@returns the time, formatted as Java's `LocalTime.toString()`

## toJSON

@returns the same text as `toString()`
