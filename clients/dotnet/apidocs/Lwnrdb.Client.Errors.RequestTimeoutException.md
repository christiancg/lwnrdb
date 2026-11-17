No reply arrived within `ClientSettings.RequestTimeout`. The connection was closed, so the request's outcome is unknown.

## RequestTimeoutException()
Creates an exception with a default message.

## RequestTimeoutException(string)
Creates an exception with a message.

@param message the message

## RequestTimeoutException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## RequestTimeoutException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
