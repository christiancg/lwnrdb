A schedule as listed.

@param Name the schedule's name
@param ProcedureName the procedure it calls
@param Cron its cron expression
@param Interval its interval
@param Enabled whether it runs
@param Version its version
@param Definer whose authority it runs with
@param NextRunAt when it runs next
@param Raw the row as the server sent it

## ScheduleInfo(string, string, string, TimeSpan?, bool, long, string, DateTimeOffset?, Document)
Creates the description.

@param Name the schedule's name
@param ProcedureName the procedure it calls
@param Cron its cron expression
@param Interval its interval
@param Enabled whether it runs
@param Version its version
@param Definer whose authority it runs with
@param NextRunAt when it runs next
@param Raw the row as the server sent it

## Cron
The cron expression, or null for an interval schedule.

## Definer
The user whose authority the schedule runs with.

## Enabled
Whether the schedule runs.

## Interval
The time between runs, or null for a cron schedule.

## Name
The schedule's name.

## NextRunAt
When the schedule runs next, or null when unknown.

## ProcedureName
The procedure the schedule calls.

## Raw
The row as the server sent it.

## Version
The schedule's version, which `IfVersion` takes.
