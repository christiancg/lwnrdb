# AsyncSchedules

The scheduled procedure runs of a database.

The sync class `Schedules` has the same members; its methods block and return the value directly.

## save

Saves a schedule; it takes exactly one of `cron` and `interval_ms`.

Returns:
    the schedule's new version

## delete

Deletes a schedule by name.

## list

Lists the database's schedules.

Args:
    include_args: also return each schedule's arguments
