Receives the events of a listen. Pass it to `Collection<T>.ListenAsync`.

Callbacks of one subscription are called one at a time and in arrival order. A handler that throws ends its subscription and reports the exception to `OnError`.

@param OnUpdate called with the current result when the listen starts and whenever a write changes it; the token is cancelled when the subscription ends

## ListenHandler(Func<ListenUpdate<T>, CancellationToken, ValueTask>)
Creates a handler.

@param OnUpdate called with each update; the token is cancelled when the subscription ends

## OnEnded
Called when the server ends the listen, for example because the collection was dropped. Optional.

## OnError
Called when the connection is lost or the handler throws. Optional.

## OnUpdate
Called with each update.
