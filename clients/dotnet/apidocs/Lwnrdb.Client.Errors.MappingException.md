A value could not be mapped between .NET and a document: a number the server cannot store faithfully, text that looks like a custom value, a document that does not fit the type, or a malformed custom value.

## MappingException()
Creates an exception with a default message.

## MappingException(string)
Creates an exception with a message.

@param message the message

## MappingException(string, Exception)
Creates an exception with a message and the exception that caused it.

@param message the message
@param innerException the cause, or null

## MappingException(string, string, Status?, string, Exception)
Creates an exception from a server refusal.

@param message the message
@param rawCode the server's error code such as `404-4`, or null
@param status the reply's status, or null
@param operation the operation that was refused, or null
@param innerException the cause, or null
