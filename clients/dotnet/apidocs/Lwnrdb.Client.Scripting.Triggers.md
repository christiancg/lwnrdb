The triggers of one collection. Obtained from `Collection<T>.Triggers()`.

A trigger runs a procedure when documents are created, updated or deleted. A `Before` trigger can accept, replace or reject a write; an `After` trigger runs once the write is committed.

## DeleteAsync(string, CancellationToken)
Deletes a trigger.

@param name the trigger
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.NotFoundException with code `404-9` when the trigger does not exist

## ListAsync(CancellationToken)
Lists the collection's triggers.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the triggers

## SaveAsync(TriggerDefinition, CancellationToken)
Saves a trigger, creating it or replacing the stored one.

@param definition the trigger
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns its new version and definer
@exception System.ArgumentException when the definition names no event
@exception Lwnrdb.Client.Errors.ConflictException with code `409-8` when `IfVersion` does not match the stored version

## TestAsync(string, TriggerEvent, Document, CancellationToken)
Runs a `Before` trigger against a document without writing it.

@param name the trigger
@param triggerEvent the event to simulate
@param sample the document to run it on
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the trigger's decision and the document it would store
