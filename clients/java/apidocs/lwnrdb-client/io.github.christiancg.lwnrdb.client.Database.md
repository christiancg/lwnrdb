One database of the server. Obtained from `LwnrdbClient.database(name)`; nothing is sent until a method is called.

## name()

@return the database's name

## create()

Creates the database.

## drop()

Drops the database with every collection, procedure and schedule in it.

## listCollections()

@return the names of the database's collections

## collection(String)

@param collection the collection's name
@return the collection, mapping documents as `Document`

## collection(String, Class)

Maps documents to a POJO or record through Jackson. A property named `id` maps to `_id` unless the type
also declares `_id`.

@param collection the collection's name
@param type the class documents map to
@param <T> the mapped type
@return the typed collection

## collection(String, DocumentCodec)

@param collection the collection's name
@param codec maps between documents and `T`
@param <T> the mapped type
@return the typed collection

## stats()

@return this database's part of `GET_DATABASE_STATS`: totals and per-collection memory

## setOwners(List)

Replaces the database's owners. Admin only.

@param owners existing usernames

## runScript(String)

Runs a SimpleJS program scoped to this database.

@param script the program
@return its result, logs and run id

## runScript(String, Document)

Runs a SimpleJS program scoped to this database.

@param script the program
@param args the value the script imports as `args`
@return its result, logs and run id

## procedures()

@return the database's stored procedures

## schedules()

@return the database's scheduled procedures

## listTriggers()

@return every trigger of every collection in the database
