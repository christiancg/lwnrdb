# ListenHandler

Receives a live query's results.

## onUpdate

Called with the full result when the listen starts, then whenever a write changes it. Updates arrive in
order; an async handler finishes before the next update starts.

@param update the result

## onEnded

Called when the server ends the listen; the subscription is closed.

@param reason the server's reason

## onError

Called when a handler threw or the connection was lost; a lost connection ends the subscription.

@param error what failed
