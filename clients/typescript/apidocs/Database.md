# Database

One database of the server. Obtained from `LwnrdbClient.database(name)`; the name is validated
locally, and nothing is sent until a method is called.

## name

The database's name.

## create

Creates the database. A database that already exists is refused with `409-2`.

## drop

Drops the database with every collection, procedure and schedule in it.

## listCollections

@returns the names of the database's collections

## collection

Names a collection of this database.

Without a codec, documents are plain objects: `T` describes them for the compiler only, and reads return
`Stored<T>`, which adds `_id`. With a codec, `encode` runs before every write and `decode` after every read.

@param name 3-64 characters from `A-Z a-z 0-9 _ -`
@param codec optional mapping between your type and stored documents
@returns the collection

## stats

@returns the database's statistics document

## setOwners

Replaces the database's owners. Admin only.

@param owners the usernames that own the database

## runScript

Runs a SimpleJS script against the database.

@param script the script source
@param args optional arguments, visible to the script
@returns the script's value, its logs and its run id

## procedures

@returns the database's stored procedures

## schedules

@returns the database's scheduled procedures

## listTriggers

@returns every trigger on every collection of the database
