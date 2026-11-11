The server is temporarily unable to serve the request (`503-*`). Retryable codes are retried automatically by the `RetryPolicy`; the rest are reported at once.

## UnavailableException()
Creates an exception with a default message.

## UnavailableException(string)
Creates an exception with a message.

@param message the message

## UnavailableException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## UnavailableException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
