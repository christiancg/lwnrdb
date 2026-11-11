The entry point of the library: a pool of authenticated connections to one LWNRDB server or cluster.

Create one with `ConnectAsync` and share it for the life of the application; it is safe to use from many threads. Every request borrows a pooled connection for the length of one round trip, except transactions, which pin one connection until they end, and listens, which use dedicated connections. Disposing the client closes every connection.

## Admin()
Returns the administration facade: users, distributed transactions, running scripts and trigger runs. Every operation behind it is admin only.

@returns the admin facade

## BeginTransactionAsync(CancellationToken)
Opens a transaction on a pinned connection. The transaction is rolled back when it is disposed without a commit.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the open transaction
@exception Lwnrdb.Client.Errors.PoolExhaustedException when no pooled connection became free within `ClientSettings.AcquireTimeout`

## ChangePasswordAsync(string, string, CancellationToken)
Changes the password of the user the client is authenticated as.

@param currentPassword the user's current password
@param newPassword the new password
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception System.InvalidOperationException when the client was created without `ClientSettings.Credentials`
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-6` when the current password is wrong

## ConnectAsync(ClientSettings, CancellationToken)
Validates the settings, opens the first connection and returns a ready client.

The connection is authenticated with `ClientSettings.Credentials` when they are set. The remaining pool connections open lazily.

@param settings the connection settings; when null, a default `ClientSettings` that connects to `localhost:8989` without credentials
@param ct cancels the connection attempt
@returns the connected client
@exception System.ArgumentException when a setting is out of range
@exception Lwnrdb.Client.Errors.ConnectionLostException when no host accepts a connection
@exception Lwnrdb.Client.Errors.AuthenticationException when the credentials are refused

## Database(string)
Returns a handle for one database. No request is sent, and the database need not exist yet.

@param name the database name: 3 to 64 characters from `A-Z a-z 0-9 _ -`
@returns the database handle
@exception System.ArgumentException when the name is not valid

## DatabaseStatsAsync(CancellationToken)
Reads the statistics of every database the user can see (`GET_DATABASE_STATS`).

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the statistics as the server reports them

## DisposeAsync()
Closes every listen and pooled connection. Connections still borrowed are given `ClientSettings.ShutdownTimeout` to finish, then closed.

@returns a task that completes when the client is closed

## ListDatabasesAsync(CancellationToken)
Lists the databases the user can see.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the database names

## ServerInfoAsync(CancellationToken)
Reads the server's state and version (`SERVER_INFO`). The server answers it even before authentication.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the server information

## TransactionAsync(Func<Transaction, CancellationToken, Task>, CancellationToken)
Runs a body inside a transaction. The transaction commits when the body returns and rolls back when it throws.

If the body already committed or rolled back the transaction, nothing more is sent.

@param body the work to run; every request that must be part of the transaction goes through the transaction it receives
@param ct cancels the wait and is passed to the body
@exception Lwnrdb.Client.Errors.ConflictException when the commit is refused, for example `409-5` on a lock timeout

## TransactionAsync<TResult>(Func<Transaction, CancellationToken, Task<TResult>>, CancellationToken)
Runs a body inside a transaction and returns its result. The transaction commits when the body returns and rolls back when it throws.

@typeparam TResult the type the body returns
@param body the work to run; every request that must be part of the transaction goes through the transaction it receives
@param ct cancels the wait and is passed to the body
@returns the body's result, once the commit succeeded
@exception Lwnrdb.Client.Errors.ConflictException when the commit is refused, for example `409-5` on a lock timeout

## Settings
The validated settings the client was created with.
