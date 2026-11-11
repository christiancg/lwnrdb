The base class of every error the client reports: a refusal by the server or a failure of the client itself.

A refusal carries the server's error code (`RawCode`, and `Code` when it is in the catalogue), the status and the operation that was refused. The subclasses group refusals by code range; catch `LwnrdbException` to handle them all.

## LwnrdbException()
Creates an exception with a default message.

## LwnrdbException(string)
Creates an exception with a message.

@param message the message

## LwnrdbException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## LwnrdbException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null

## HasCode(ErrorCode)
Tells whether the refusal has a code.

@param code the catalogue code to compare with
@returns true when `RawCode` equals the code

## HasCode(string)
Tells whether the refusal has a code.

@param rawCode the code such as `409-2`
@returns true when `RawCode` equals it

## Code
The catalogue entry for `RawCode`, or null when the refusal has no code or the code is not in the catalogue.

## Operation
The name of the operation that was refused, such as `SAVE`, or null.

## RawCode
The server's error code such as `404-4`, or null for a failure of the client itself.

## Status
The status of the reply that carried the refusal, or null.
