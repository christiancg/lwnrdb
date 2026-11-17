# AsyncTriggers

The triggers of a collection: stored procedures run when documents are created, updated or deleted.

The sync class `Triggers` has the same members; its methods block and return the value directly.

## save

Saves a trigger.

Returns:
    the trigger's new version and its definer

## delete

Deletes a trigger by name.

## list

Lists the collection's triggers.

## test

Runs a trigger's procedure against a sample document without writing anything.

Args:
    name: the trigger
    event: the event to simulate
    sample: the document the procedure receives
