Trigger runs waiting for a retry or dead-lettered after their last attempt. Obtained from `Admin.TriggerRuns()`. Admin only.

## ListAsync(TriggerRunStatus?, CancellationToken)
Lists trigger runs.

@param status the status to keep, or null for every run
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the runs

## ResolveAsync(string, TriggerRunDecision, CancellationToken)
Replays or discards a trigger run.

@param runId the run's id
@param decision replay or discard
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns true when the run was found
