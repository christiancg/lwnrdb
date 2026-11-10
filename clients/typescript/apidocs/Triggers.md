# Triggers

The triggers of one collection.

## save

Creates or replaces a trigger.

@param definition the trigger; at least one event
@returns its version and definer

## delete

@param name the trigger to delete

## list

@returns the collection's triggers

## test

Runs a trigger against a sample document without writing anything.

@param name the trigger
@param event the event to simulate
@param sample the document
@returns the decision, the resulting document and the logs
