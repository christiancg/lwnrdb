A live query. `await using` closes it at the end of the block.

## DisposeAsync()
Stops the listen. A listen the server already ended sends nothing.

@returns a task that completes when the listen is stopped

## IsActive
Whether updates still arrive: false once the subscription is disposed, ended by the server or its connection is lost.

## ListenId
The id the server gave the listen.
