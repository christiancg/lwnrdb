Everything a user may do. Built with `Permissions.builder()`; anything not granted is denied.

## COLLECTION_SEPARATOR

Separates database and collection in a collection grant's key: `shop|orders`.

## builder()

@return a builder that grants nothing

## admin()

@return `true` for a superadmin, who may do everything

## global()

@return the server-wide permissions

## databases()

@return the access per database

## collections()

@return the access per `database|collection`

## scripts()

@return the script access per database
