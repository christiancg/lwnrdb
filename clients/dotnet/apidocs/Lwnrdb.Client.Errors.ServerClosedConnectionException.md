The server closed the connection and said why, for example because it is shutting down or at its connection limit.

## ServerClosedConnectionException()
Creates an exception with a default message.

## ServerClosedConnectionException(string)
Creates an exception with a message.

@param message the message

## ServerClosedConnectionException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## ServerClosedConnectionException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
