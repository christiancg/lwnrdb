# LwnrdbClient

The entry point: a pool of authenticated connections to one LWNRDB server or cluster.

Create one with `LwnrdbClient.connect(settings)` and share it; every request borrows a pooled connection until its
reply arrives, so `poolSize` bounds how many requests run at once. Close it with `close()` or `await using`.

```ts
await using client = await LwnrdbClient.connect({ credentials: { username: "admin", password } });
const orders = client.database("shop").collection("orders");
```

## settings

The validated settings the client runs with, defaults filled in.

## connect

Opens one connection, authenticates it and returns the client. Further connections open as requests need them.

@param settings the hosts, credentials, TLS and limits; every field is optional
@returns the connected client
@throws ConnectionLostError when no host accepts the connection, or AuthenticationError when the login is refused

## database

Names a database. Nothing is sent until a method of the returned object is called.

@param name 3-64 characters from `A-Z a-z 0-9 _ -`
@returns the database

## listDatabases

@returns the names of the databases the user can see

## serverInfo

Asks the server which build it runs and whether it is ready.

@returns the server's state, version and the raw reply

## databaseStats

@returns the server-wide statistics document

## changePassword

Changes the password of the user the client logged in as. Connections already open stay authenticated.

@param currentPassword the password in use
@param newPassword the new password
@throws LwnrdbError when the client has no credentials

## admin

@returns the administrative operations: users, distributed transactions, scripts and trigger runs

## beginTransaction

Starts a transaction on a connection of its own, taken from the pool until the transaction ends. Prefer
`transaction(body)`, which commits and rolls back for you.

@returns the open transaction

## transaction

Runs a body inside a transaction: it commits when the body resolves and rolls back when it throws or
rejects, rethrowing the body's error. A transaction the body already committed or rolled back is left as it is.

@param body receives the transaction; use its collections for every read and write that belongs to it
@returns what the body returned

## close

Closes the listen connections, then the pool: idle connections are closed at once, connections in use once
their request ends, waiting at most `shutdownTimeoutMs`. Later requests fail with `ConnectionLostError`.

## Symbol.asyncDispose

Closes the client, so `await using client = ...` closes it at the end of the block.
