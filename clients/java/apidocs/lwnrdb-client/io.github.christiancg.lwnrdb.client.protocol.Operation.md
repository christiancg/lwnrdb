The wire operations a client may send, from the server's catalogue. The fault-injection operations
are test-only and left out.

## BULK_SAVE

Scope collection; allowed inside a transaction.

## SAVE

Scope collection; allowed inside a transaction.

## FIND_BY_ID

Scope collection; allowed inside a transaction.

## AGGREGATE

Scope collection; allowed inside a transaction.

## DELETE

Scope collection; allowed inside a transaction.

## CREATE_DATABASE

Scope database.

## DROP_DATABASE

Scope database.

## LIST_DATABASES

Scope none.

## CREATE_COLLECTION

Scope collection.

## DROP_COLLECTION

Scope collection.

## LIST_COLLECTIONS

Scope database.

## CREATE_INDEX

Scope collection.

## DROP_INDEX

Scope collection.

## REINDEX

Scope collection.

## SAVE_SCHEMA

Scope collection.

## DELETE_SCHEMA

Scope collection.

## GET_SCHEMA

Scope collection.

## CLOSE_CONNECTION

Scope none; allowed inside a transaction.

## SERVER_INFO

Scope none; allowed inside a transaction.

## AUTHENTICATE

Scope none.

## CREATE_USER

Scope none; admin only.

## DELETE_USER

Scope none; admin only.

## CHANGE_PERMISSIONS

Scope none; admin only.

## SET_DATABASE_OWNERS

Scope database; admin only.

## LIST_USERS

Scope none; admin only.

## SET_PASSWORD

Scope none.

## GET_DATABASE_STATS

Scope database.

## LISTEN

Scope collection.

## STOP_LISTEN

Scope none.

## START_TRANSACTION

Scope none; allowed inside a transaction.

## COMMIT_TRANSACTION

Scope none; allowed inside a transaction.

## ROLLBACK_TRANSACTION

Scope none; allowed inside a transaction.

## RESOLVE_TRANSACTION

Scope none; admin only.

## LIST_TRANSACTIONS

Scope none; admin only.

## RUN_SCRIPT

Scope database.

## SAVE_PROCEDURE

Scope database.

## DELETE_PROCEDURE

Scope database.

## LIST_PROCEDURES

Scope database.

## CALL_PROCEDURE

Scope database.

## SAVE_TRIGGER

Scope collection.

## DELETE_TRIGGER

Scope collection.

## LIST_TRIGGERS

Scope optional collection.

## SAVE_SCHEDULE

Scope database.

## DELETE_SCHEDULE

Scope database.

## LIST_SCHEDULES

Scope database.

## LIST_SCRIPTS

Scope none; admin only.

## CANCEL_SCRIPT

Scope none; admin only.

## TEST_TRIGGER

Scope collection.

## LIST_TRIGGER_RUNS

Scope none; admin only.

## RESOLVE_TRIGGER_RUN

Scope none; admin only.

## scope()

@return what the operation addresses

## adminOnly()

@return `true` when only admins may send it

## allowedInTransaction()

@return `true` when it may be sent while a transaction is open

## isRead()

@return `true` when it changes nothing, so a lost connection may be retried
