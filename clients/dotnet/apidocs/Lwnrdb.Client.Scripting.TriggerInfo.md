A trigger as listed.

@param Name the trigger's name
@param Collection its collection
@param Events the events it fires on
@param ProcedureName the procedure it runs
@param Timing `after` or `before`
@param Mode `document` or `batch`
@param Enabled whether it fires
@param Version its version
@param Definer whose authority it runs with
@param Raw the row as the server sent it

## TriggerInfo(string, string, IReadOnlyList<string>, string, string, string, bool, long, string, Document)
Creates the description.

@param Name the trigger's name
@param Collection its collection
@param Events the events it fires on
@param ProcedureName the procedure it runs
@param Timing `after` or `before`
@param Mode `document` or `batch`
@param Enabled whether it fires
@param Version its version
@param Definer whose authority it runs with
@param Raw the row as the server sent it

## Collection
The trigger's collection, or null when the server does not report it.

## Definer
The user whose authority the trigger runs with.

## Enabled
Whether the trigger fires.

## Events
The events the trigger fires on, as the server names them.

## Mode
`document` or `batch`.

## Name
The trigger's name.

## ProcedureName
The procedure the trigger runs.

## Raw
The row as the server sent it.

## Timing
`after` or `before`.

## Version
The trigger's version, which `IfVersion` takes.
