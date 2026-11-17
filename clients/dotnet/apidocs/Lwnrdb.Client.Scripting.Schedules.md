The scheduled procedures of one database. Obtained from `Database.Schedules()`.

## DeleteAsync(string, CancellationToken)
Deletes a schedule.

@param name the schedule
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown

## ListAsync(bool?, CancellationToken)
Lists the schedules.

@param includeArgs true to include each schedule's arguments; null leaves it to the server's default
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the schedules

## SaveAsync(ScheduleDefinition, CancellationToken)
Saves a schedule, creating it or replacing the stored one.

@param definition the schedule; exactly one of `Cron` and `Interval` must be set
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the new version
@exception System.ArgumentException when both or neither of `Cron` and `Interval` are set
@exception Lwnrdb.Client.Errors.ConflictException with code `409-8` when `IfVersion` does not match the stored version
