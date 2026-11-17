# LwnrdbError

Every error the client raises. A refusal from the server carries its `errorCode` as `rawCode`;
the subclass is chosen from the code's range.

## rawCode

The server's error code, such as `404-11`, or `undefined` for a client-side failure.

## status

The reply's status, when the server sent one.

## operation

The operation the refused reply answered.

## code

The catalogue entry of `rawCode`, or `undefined` when it is unknown.

## hasCode

@param code an error code, such as `ErrorCode.NO_RESULTS`
@returns whether this error carries it
