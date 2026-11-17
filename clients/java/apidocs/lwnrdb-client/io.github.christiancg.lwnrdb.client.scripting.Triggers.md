The triggers of one collection. Obtained from `Collection.triggers()`.

## Triggers(ClientContext, String, String)

Internal: use `Collection.triggers()`.

@param context the client's internal context
@param database the database
@param collection the collection

## save(TriggerDefinition)

@param trigger the trigger
@return its new version and definer

## delete(String)

@param name the trigger

## list()

@return the collection's triggers

## test(String, TriggerEvent, Document)

Runs a `before` trigger against a document without writing it.

@param name the trigger
@param event the event to simulate
@param document the document
@return the trigger's decision and the document it would store
