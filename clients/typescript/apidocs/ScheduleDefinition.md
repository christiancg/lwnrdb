# ScheduleDefinition

A schedule to save. It takes exactly one of `cron` and `intervalMs`.

## name

Its name.

## procedureName

The procedure it calls.

## cron

A cron expression.

## intervalMs

A fixed interval in milliseconds.

## args

Optional arguments for each run.

## timeoutMs

Optional time limit of each run.

## description

Optional description.

## enabled

Optional; enabled by default.

## ifVersion

Optional: save only when the stored version is this one.
