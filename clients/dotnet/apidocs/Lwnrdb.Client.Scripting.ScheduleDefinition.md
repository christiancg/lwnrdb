A schedule to save: a procedure called on a cron expression or at a fixed interval.

Set exactly one of `Cron` and `Interval`.

@param Name the schedule's name
@param ProcedureName the procedure it calls

## ScheduleDefinition(string, string)
Creates a definition.

@param Name the schedule's name
@param ProcedureName the procedure it calls

## Args
The value the procedure imports as `args`, or null.

## Cron
A five-field cron expression, or null when `Interval` is used.

## Description
Free text describing the schedule, or null.

## Enabled
False keeps the schedule stored but idle; null leaves it to the server's default, which is enabled.

## IfVersion
The version the stored definition must have, or the save is refused with `409-8`. Null saves unconditionally.

## Interval
The time between runs, or null when `Cron` is used. The server refuses an interval that is too small (`400-35`).

## Name
The schedule's name.

## ProcedureName
The procedure the schedule calls.

## Timeout
How long one run may take, or null for the server's default.
