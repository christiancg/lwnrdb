The scripts running on the server. Obtained from `Admin.Scripts()`. Admin only.

## CancelAsync(string, CancellationToken)
Cancels a running script.

@param runId the run's id
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns true when the run was found and stopped

## ListAsync(CancellationToken)
Lists the running scripts, procedures, triggers and schedules.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the running scripts
