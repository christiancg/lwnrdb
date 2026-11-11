A stored procedure to save.

@param Name the procedure's name
@param Script the SimpleJS module source

## ProcedureDefinition(string, string)
Creates a definition.

@param Name the procedure's name
@param Script the SimpleJS module source

## Description
Free text describing the procedure, or null.

## Enabled
False keeps the procedure stored but uncallable; null leaves it to the server's default, which is enabled.

## IfVersion
The version the stored definition must have, or the save is refused with `409-8`. Null saves unconditionally.

## Name
The procedure's name.

## Script
The SimpleJS module source.
