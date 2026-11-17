# AsyncLwnrdbClient

A client for one LWNRDB server or cluster: a pool of authenticated connections, plus dedicated connections for
live queries.

Create one with `await AsyncLwnrdbClient.connect(settings)` and share it; every request borrows a pooled connection
for the length of one request, so `pool_size` bounds how many requests run at once. `async with` closes it.

The sync class `LwnrdbClient` has the same members; its methods block and return the value directly.

## connect

Opens the pool and checks that one connection authenticates.

Args:
    settings: the connection settings; `ClientSettings()` (localhost:8989, no credentials) when omitted

Raises:
    ConnectionLostError: no host could be reached
    AuthenticationError: the credentials were refused

## settings

The settings the client was created with.

## database

Returns the database with this name. Nothing is sent until a method is called on it.

Args:
    name: 3-64 characters from A-Z a-z 0-9 _ -

## list_databases

Lists the databases the caller can see.

## server_info

Returns the server's build and readiness state. Allowed before authentication.

## database_stats

Returns the server-wide statistics document (`GET_DATABASE_STATS` without a database).

## change_password

Changes the password of the user the client authenticated as.

Args:
    current_password: the password in use
    new_password: the new password

Raises:
    LwnrdbError: the client has no credentials

## admin

Returns the administration operations: users, distributed transactions, scripts and trigger runs.

## begin_transaction

Starts a transaction on a connection it pins until the transaction ends.

End it with `commit()` or `rollback()`; leaving it as a context manager rolls back a transaction still open.

## transaction

A context manager that starts a transaction, commits it when the block ends normally and rolls it back
when the block raises.

```python
async with client.transaction() as tx:
    await tx.collection("shop", "orders").delete("o-1")
```

## close

Closes the live-query connections and the pool. Requests still running fail with `ConnectionLostError`.

## __aenter__

Returns the client itself.

## __aexit__

Closes the client.
