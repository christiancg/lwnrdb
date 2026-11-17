# Schedules

The scheduled procedures of one database.

## save

Creates or replaces a schedule.

@param definition the schedule; exactly one of `cron` and `intervalMs`
@returns its new version

## delete

@param name the schedule to delete

## list

@param includeArgs whether to include each schedule's arguments
@returns the schedules
