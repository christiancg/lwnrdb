# AsyncSubscription

A live query registration returned by `listen`. Closing it stops the updates.

The sync class `Subscription` has the same members; its methods block and return the value directly.

## listen_id

The server's id of the listen.

## is_active

Whether updates can still arrive: not closed, not ended and the connection still open.

## close

Stops the listen. Closing an ended or closed subscription sends nothing.

## __aenter__

Returns the subscription itself.

## __aexit__

Closes the subscription.
