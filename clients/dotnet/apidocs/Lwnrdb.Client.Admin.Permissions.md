What a user may do. Used to create a user and to replace a user's permissions.

An empty `Permissions` grants nothing. `Admin` makes the user a superadmin, who may do everything.

## Permissions()
Creates permissions that grant nothing.

## CollectionKey(string, string)
Builds the key of a collection in `Collections`, which is `database|collection`.

@param database the database name
@param collection the collection name
@returns the key

## Admin
True for a superadmin, who is not limited by the other permissions.

## Collections
Access per collection, keyed by `Permissions.CollectionKey`.

## Databases
Access per database, keyed by the database name.

## Global
The server-wide permissions.

## Scripts
Script access per database, keyed by the database name.
