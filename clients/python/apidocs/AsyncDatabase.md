# AsyncDatabase

One database of the server, obtained from `client.database(name)`.

The sync class `Database` has the same members; its methods block and return the value directly.

## name

The database's name.

## create

Creates the database.

Raises:
    ConflictError: it already exists (`409-2`)

## drop

Drops the database with every collection, procedure and schedule in it.

## list_collections

Lists the database's collections.

## collection

Returns one collection of this database.

With only a name, documents are plain dicts. With a dataclass `model`, documents map through
`DataclassCodec(model)`; with `codec`, through that `DocumentCodec`.

Args:
    name: 3-64 characters from A-Z a-z 0-9 _ -
    model: a dataclass type to map documents to
    codec: a codec that maps your type to and from a document

## stats

Returns this database's statistics document.

## set_owners

Replaces the database's owners. Admin only.

Args:
    owners: the usernames that own the database

## run_script

Runs an ad-hoc SimpleJS script in this database.

Args:
    script: the script source
    args: a document the script reads as its arguments

Returns:
    the script's result, its logs and its run id

## procedures

Returns the stored procedures of this database.

## schedules

Returns the scheduled procedures of this database.

## list_triggers

Lists the triggers of every collection in this database.
