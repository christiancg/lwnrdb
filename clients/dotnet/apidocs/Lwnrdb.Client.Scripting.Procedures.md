The stored procedures of one database. Obtained from `Database.Procedures()`.

Procedures are SimpleJS modules that triggers, schedules and `CallAsync` run. Saving one needs the script permission `Manage`.

## CallAsync(string, Document, CancellationToken)
Calls a procedure.

@param name the procedure
@param args the value it imports as `args`, or null for none
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the result and log lines
@exception Lwnrdb.Client.Errors.NotFoundException with code `404-8` when the procedure does not exist

## DeleteAsync(string, CancellationToken)
Deletes a procedure.

@param name the procedure
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.ForbiddenException with code `403-4` when a trigger or schedule that runs as another user is bound to it

## ListAsync(bool?, CancellationToken)
Lists the procedures.

@param includeSource true to include each procedure's source, which needs script `Manage`; null leaves it to the server's default
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the procedures

## SaveAsync(ProcedureDefinition, CancellationToken)
Saves a procedure, creating it or replacing the stored one.

@param definition the procedure
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the new version
@exception Lwnrdb.Client.Errors.ConflictException with code `409-8` when `IfVersion` does not match the stored version
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-13` when the source cannot be parsed
