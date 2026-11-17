The entry point: a pool of authenticated connections to one LWNRDB server (or cluster).

```java
try (LwnrdbClient client = LwnrdbClient.connect(settings)) {
    Collection<Document> orders = client.database("shop").collection("orders");
    orders.save(Document.of("_id", "o-1", "status", "active"));
}
```

The client is thread-safe. Close it to release its connections.

## connect(ClientSettings)

Creates a client and opens one connection to check the address and credentials.

@param settings the connection settings
@return the client
@throws io.github.christiancg.lwnrdb.client.error.AuthenticationException when the credentials are refused
@throws io.github.christiancg.lwnrdb.client.error.ConnectionLostException when no host can be reached

## settings()

@return the settings the client was created with

## database(String)

@param name the database's name, 3-64 characters from `A-Z a-z 0-9 _ -`
@return the database

## listDatabases()

@return the names of the databases the user can see

## serverInfo()

@return the server's state and, once authenticated, its version

## databaseStats()

@return the server's memory usage and per-database totals

## changePassword(String, String)

Changes the password of the user the client authenticates as.

@param currentPassword the current password
@param newPassword the new password

## codecFor(Class)

The codec `Database.collection(name, Class)` uses, built with this client's Jackson settings
(custom value types, temporal zone, the caller's mapper). Integrations such as Spring Data reuse it so a class is
stored the same way everywhere.

@param type the class documents map to
@param <T> the mapped type
@return the codec

## admin()

@return the admin-only operations

## beginTransaction()

Starts a transaction on a connection it keeps until commit or rollback. Prefer `transaction(body)`.

@return the open transaction; close it to roll back

## transaction(TransactionBody)

Runs a body inside a transaction: it commits when the body returns and rolls back when it throws. A commit
whose outcome is unknown (`500-33`, `500-34`) is not rolled back.

@param body the work
@param <R> the body's result type
@return what the body returned
