The base of every error the client raises. A server refusal carries the server's `errorCode`
(`NNN-N`) and `status`; a client-side failure carries neither.

## LwnrdbException(String, String, Status)

@param message the server's message and code
@param rawCode the server's `errorCode`
@param status the server's `status`

## LwnrdbException(String, Throwable)

@param message what failed
@param cause the underlying failure, or `null`

## rawCode()

@return the server's `errorCode` exactly as sent, empty for a client-side failure

## errorCode()

@return the code as a catalogue constant, empty when the client does not know it

## status()

@return the server's `status`

## hasCode(ErrorCode)

@param code a catalogue constant
@return `true` when the server answered that code
