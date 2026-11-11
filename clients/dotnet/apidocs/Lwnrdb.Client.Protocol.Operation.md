One operation of the wire protocol: its name, scope and rules.

The catalogue is shared by every LWNRDB client (`clients/protocol/operations.json`). The `Operation` members are instances, not enumeration values.

## BulkSave
The `BULK_SAVE` operation. Inserts or replaces many documents.

## Save
The `SAVE` operation. Inserts or replaces one document.

## FindById
The `FIND_BY_ID` operation. Reads one document by its `_id`.

## Aggregate
The `AGGREGATE` operation. Runs a query pipeline on a collection.

## Delete
The `DELETE` operation. Deletes a document.

## CreateDatabase
The `CREATE_DATABASE` operation. Creates a database.

## DropDatabase
The `DROP_DATABASE` operation. Drops a database.

## ListDatabases
The `LIST_DATABASES` operation. Lists the databases.

## CreateCollection
The `CREATE_COLLECTION` operation. Creates a collection.

## DropCollection
The `DROP_COLLECTION` operation. Drops a collection.

## ListCollections
The `LIST_COLLECTIONS` operation. Lists a database's collections.

## CreateIndex
The `CREATE_INDEX` operation. Creates field indexes.

## DropIndex
The `DROP_INDEX` operation. Drops a field index.

## Reindex
The `REINDEX` operation. Rebuilds field indexes, or the primary-key index.

## SaveSchema
The `SAVE_SCHEMA` operation. Saves a collection's schema.

## DeleteSchema
The `DELETE_SCHEMA` operation. Deletes a collection's schema.

## GetSchema
The `GET_SCHEMA` operation. Reads a collection's schema.

## CloseConnection
The `CLOSE_CONNECTION` operation. Closes the connection.

## ServerInfo
The `SERVER_INFO` operation. Reads the server's state and version.

## Authenticate
The `AUTHENTICATE` operation. Authenticates the connection.

## CreateUser
The `CREATE_USER` operation. Creates a user.

## DeleteUser
The `DELETE_USER` operation. Deletes a user.

## ChangePermissions
The `CHANGE_PERMISSIONS` operation. Replaces a user's permissions.

## SetDatabaseOwners
The `SET_DATABASE_OWNERS` operation. Replaces a database's owners.

## ListUsers
The `LIST_USERS` operation. Lists users.

## SetPassword
The `SET_PASSWORD` operation. Sets a user's password.

## GetDatabaseStats
The `GET_DATABASE_STATS` operation. Reads database statistics.

## Listen
The `LISTEN` operation. Registers a listenable query.

## StopListen
The `STOP_LISTEN` operation. Stops a listen.

## StartTransaction
The `START_TRANSACTION` operation. Starts a transaction on the connection.

## CommitTransaction
The `COMMIT_TRANSACTION` operation. Commits the connection's transaction.

## RollbackTransaction
The `ROLLBACK_TRANSACTION` operation. Rolls back the connection's transaction.

## ResolveTransaction
The `RESOLVE_TRANSACTION` operation. Forces the outcome of a distributed transaction.

## ListTransactions
The `LIST_TRANSACTIONS` operation. Lists unresolved distributed transactions.

## RunScript
The `RUN_SCRIPT` operation. Runs a script.

## SaveProcedure
The `SAVE_PROCEDURE` operation. Saves a stored procedure.

## DeleteProcedure
The `DELETE_PROCEDURE` operation. Deletes a stored procedure.

## ListProcedures
The `LIST_PROCEDURES` operation. Lists a database's stored procedures.

## CallProcedure
The `CALL_PROCEDURE` operation. Calls a stored procedure.

## SaveTrigger
The `SAVE_TRIGGER` operation. Saves a trigger.

## DeleteTrigger
The `DELETE_TRIGGER` operation. Deletes a trigger.

## ListTriggers
The `LIST_TRIGGERS` operation. Lists triggers.

## SaveSchedule
The `SAVE_SCHEDULE` operation. Saves a schedule.

## DeleteSchedule
The `DELETE_SCHEDULE` operation. Deletes a schedule.

## ListSchedules
The `LIST_SCHEDULES` operation. Lists a database's schedules.

## ListScripts
The `LIST_SCRIPTS` operation. Lists the running scripts.

## CancelScript
The `CANCEL_SCRIPT` operation. Cancels a running script.

## TestTrigger
The `TEST_TRIGGER` operation. Runs a trigger against a sample document without writing it.

## ListTriggerRuns
The `LIST_TRIGGER_RUNS` operation. Lists pending and dead trigger runs.

## ResolveTriggerRun
The `RESOLVE_TRIGGER_RUN` operation. Replays or discards a trigger run.

## Of(string)
Looks an operation up by its wire name.

@param name the wire name such as `SAVE`, or null
@returns the operation, or null when there is none

## AdminOnly
Whether only an admin user may send the operation.

## All
Every operation.

## AllowedInTransaction
Whether the operation may be sent while a transaction is open.

## IsRead
Whether the operation only reads: `FIND_BY_ID`, `AGGREGATE`, `SERVER_INFO` and the `LIST_` and `GET_` operations.

## Name
The wire name, such as `FIND_BY_ID`.

## Scope
What the request names: a database, a collection, both or neither.
