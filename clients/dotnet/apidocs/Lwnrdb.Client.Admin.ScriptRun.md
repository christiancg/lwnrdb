A script running on the server.

@param RunId the id `Scripts.CancelAsync` takes
@param Node the cluster node it runs on
@param Kind `RUN_SCRIPT`, `CALL_PROCEDURE`, a trigger or a schedule
@param Database its database
@param Name the procedure, trigger or schedule name
@param Username who started it
@param AgeMs how long it has run in milliseconds
@param Raw the row as the server sent it

## ScriptRun(string, string, string, string, string, string, long, Document)
Creates the description.

@param RunId the run's id
@param Node the cluster node it runs on
@param Kind the kind of run
@param Database its database
@param Name the procedure, trigger or schedule name
@param Username who started it
@param AgeMs how long it has run in milliseconds
@param Raw the row as the server sent it

## AgeMs
How long the run has lasted, in milliseconds.

## Database
The run's database, or null.

## Kind
The kind of run: `RUN_SCRIPT`, `CALL_PROCEDURE`, a trigger or a schedule.

## Name
The procedure, trigger or schedule name, or null for an ad-hoc script.

## Node
The cluster node the run is on, or null on a single server.

## Raw
The row as the server sent it.

## RunId
The id `Scripts.CancelAsync` takes.

## Username
Who started the run, or null.
