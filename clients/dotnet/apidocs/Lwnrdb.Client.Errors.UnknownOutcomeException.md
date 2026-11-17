A write passed its commit point but could not finish applying (`500-33`, `500-34`). It may already have taken effect, and the server finishes it before the collection is next used.

## UnknownOutcomeException()
Creates an exception with a default message.

## UnknownOutcomeException(string)
Creates an exception with a message.

@param message the message

## UnknownOutcomeException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## UnknownOutcomeException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
