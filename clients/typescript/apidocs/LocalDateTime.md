# LocalDateTime

A date-time without a zone, the server's `#datetime(...)` value. Immutable.

The server's datetime has no zone, so a JS `Date` (an instant) is stored only when the client sets `temporalZone`;
`fromDate` and `toDate` convert explicitly.

## year

The year.

## month

The month, 1-12.

## day

The day of the month.

## hour

The hour, 0-23.

## minute

The minute, 0-59.

## second

The second, 0-59.

## nanosecond

The nanosecond, 0-999999999.

## of

@param year the year
@param month the month, 1-12
@param day the day of the month
@param hour optional, 0 by default
@param minute optional, 0 by default
@param second optional, 0 by default
@param nanosecond optional, 0 by default
@returns the date-time
@throws RangeError on an impossible date or time

## parse

@param text ISO text such as `2024-01-01T10:00` or `2024-01-01T10:00:30.5`
@returns the date-time

## fromDate

@param date the instant
@param zone an IANA zone such as `Europe/Madrid`, or `UTC`
@returns the wall-clock time of the instant in that zone

## toDate

A wall-clock time inside a daylight-saving gap moves forward.

@param zone an IANA zone, or `UTC`
@returns the instant this wall-clock time names in that zone

## compareTo

@param other another date-time
@returns -1, 0 or 1

## equals

@param other any value
@returns whether it is a `LocalDateTime` with the same fields

## toString

@returns ISO text, formatted as Java's `LocalDateTime.toString()`

## toJSON

@returns the same text as `toString()`
