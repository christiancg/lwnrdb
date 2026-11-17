# AsyncProcedures

The stored procedures of a database.

The sync class `Procedures` has the same members; its methods block and return the value directly.

## save

Saves a procedure.

Returns:
    the procedure's new version

## delete

Deletes a procedure by name.

## list

Lists the database's procedures.

Args:
    include_source: also return each procedure's source

## call

Calls a procedure.

Args:
    name: the procedure
    args: a document the procedure reads as its arguments
