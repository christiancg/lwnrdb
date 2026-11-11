The connection failed before a reply arrived: it could not be opened, the server closed it, or it broke. A request that was already sent has an unknown outcome and is not retried.

## ConnectionLostException()
Creates an exception with a default message.

## ConnectionLostException(string)
Creates an exception with a message.

@param message the message

## ConnectionLostException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## ConnectionLostException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
