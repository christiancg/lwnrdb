The server ended a listen (`410-1`), for example because its collection was dropped. Reported to `ListenHandler<T>.OnEnded`.

## ListenEndedException()
Creates an exception with a default message.

## ListenEndedException(string)
Creates an exception with a message.

@param message the message

## ListenEndedException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## ListenEndedException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
