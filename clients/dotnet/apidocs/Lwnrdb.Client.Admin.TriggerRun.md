A pending or dead trigger run.

@param RunId the id `TriggerRuns.ResolveAsync` takes
@param Status `PENDING`, `DEAD` or `STAGED`
@param Database the database
@param Collection the collection
@param Trigger the trigger's name
@param Procedure the procedure it runs
@param Event the write event
@param Attempts the attempts made so far
@param LastError the last failure
@param Raw the row as the server sent it

## TriggerRun(string, string, string, string, string, string, string, long, string, Document)
Creates the description.

@param RunId the run's id
@param Status `PENDING`, `DEAD` or `STAGED`
@param Database the database
@param Collection the collection
@param Trigger the trigger's name
@param Procedure the procedure it runs
@param Event the write event
@param Attempts the attempts made so far
@param LastError the last failure
@param Raw the row as the server sent it

## Attempts
The attempts made so far.

## Collection
The collection, or null.

## Database
The database, or null.

## Event
The write event that fired the trigger, or null.

## LastError
The last failure, or null.

## Procedure
The procedure the trigger runs, or null.

## Raw
The row as the server sent it.

## RunId
The id `TriggerRuns.ResolveAsync` takes.

## Status
`PENDING`, `DEAD` or `STAGED`.

## Trigger
The trigger's name, or null.
