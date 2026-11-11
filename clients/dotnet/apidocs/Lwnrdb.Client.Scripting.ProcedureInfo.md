A procedure as listed.

@param Name the procedure's name
@param Version its version
@param Enabled whether it can be called
@param Description its description
@param Source its source, when requested and permitted
@param Raw the row as the server sent it

## ProcedureInfo(string, long, bool, string, string, Document)
Creates the description.

@param Name the procedure's name
@param Version its version
@param Enabled whether it can be called
@param Description its description
@param Source its source, when requested and permitted
@param Raw the row as the server sent it

## Description
The procedure's description, or null.

## Enabled
Whether the procedure can be called.

## Name
The procedure's name.

## Raw
The row as the server sent it.

## Source
The procedure's source, or null when it was not requested or not permitted.

## Version
The procedure's version, which `IfVersion` takes.
