No pooled connection became free within `ClientSettings.AcquireTimeout`. Nothing was sent.

## PoolExhaustedException()
Creates an exception with a default message.

## PoolExhaustedException(string)
Creates an exception with a message.

@param message the message

## PoolExhaustedException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## PoolExhaustedException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
