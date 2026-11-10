A pending or dead trigger run.

@param runId the id `TriggerRuns.resolve` takes
@param status `PENDING`, `DEAD` or `STAGED`
@param database the database
@param collection the collection
@param trigger the trigger's name
@param procedure the procedure it runs
@param event the write event
@param attempts attempts made so far
@param lastError the last failure
@param raw the row as the server sent it
