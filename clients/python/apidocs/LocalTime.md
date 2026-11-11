# LocalTime

A time of day without a zone at nanosecond precision, stored as `#time(...)`. Reads return a plain `time` when
the value fits one.

## of

Creates a time, validating every field.

## parse

Parses Java's `LocalTime` text.

## from_time

Converts a naive `time`.

## fits_time

Whether `to_time` keeps every digit.

## to_time

Converts to a `time`, dropping digits below a microsecond.
