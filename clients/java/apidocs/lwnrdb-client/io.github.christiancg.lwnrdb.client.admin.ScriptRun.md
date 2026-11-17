A script running on the server.

@param runId the id `Scripts.cancel` takes
@param node the cluster node it runs on
@param kind `RUN_SCRIPT`, `CALL_PROCEDURE`, a trigger or a schedule
@param database its database
@param name the procedure, trigger or schedule name
@param username who started it
@param ageMs how long it has run
@param raw the row as the server sent it
