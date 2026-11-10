Receives the updates of a `Collection.listen` subscription. Every method runs on the subscription's
dispatcher thread; a method that throws is reported to `onError` and the subscription goes on.

@param <T> the type rows are decoded to

## onUpdate(ListenUpdate)

@param update the full current result

## onEnded(ListenEndedException)

Called once when the server ends the listen on its own, for example because a collection it reads was dropped.

@param reason the server's `410-1` frame

## onError(Throwable)

Called when a handler method threw or the subscription's connection was lost. A lost connection ends the
subscription: listen again to resume.

@param error what went wrong
