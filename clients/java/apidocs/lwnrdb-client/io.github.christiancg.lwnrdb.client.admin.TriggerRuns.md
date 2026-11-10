Trigger runs waiting for a retry or dead-lettered after their last attempt. Admin only.

## list()

@return every pending and dead run

## list(TriggerRunStatus)

@param status the status to keep
@return the runs with that status

## resolve(String, TriggerRunDecision)

@param runId the run's id
@param decision replay or discard
@return `true` when the run was found
