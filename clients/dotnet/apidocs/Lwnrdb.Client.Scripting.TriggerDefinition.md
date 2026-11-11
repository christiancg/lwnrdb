A trigger to save.

@param Name the trigger's name
@param ProcedureName the procedure it runs
@param Events the write events it fires on, at least one

## TriggerDefinition(string, string, IReadOnlyList<TriggerEvent>)
Creates a definition.

@param Name the trigger's name
@param ProcedureName the procedure it runs
@param Events the write events it fires on, at least one

## AllowCascade
True lets the trigger's own writes fire triggers; null leaves it to the server's default.

## Enabled
False keeps the trigger stored but silent; null leaves it to the server's default, which is enabled.

## Events
The write events the trigger fires on.

## IfVersion
The version the stored definition must have, or the save is refused with `409-8`. Null saves unconditionally.

## Mode
Whether the procedure runs per document or per batch; null leaves it to the server's default.

## Name
The trigger's name.

## ProcedureName
The procedure the trigger runs.

## Timing
Whether the trigger runs before or after the write; null leaves it to the server's default.
