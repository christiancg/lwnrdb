# LocalDateTime

A date-time without a zone at nanosecond precision and Java's year range, stored as `#datetime(...)`. Reads
return a plain `datetime` when the value fits one and a `LocalDateTime` only when it does not.

## of

Creates a date-time, validating every field.

## parse

Parses Java's `LocalDateTime` text.

## from_datetime

Converts a naive `datetime`.

## fits_datetime

Whether `to_datetime` keeps every digit.

## to_datetime

Converts to a naive `datetime`, dropping digits below a microsecond.
