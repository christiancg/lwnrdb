A user as `LIST_USERS` describes it.

@param Username the user name
@param IsAdmin whether the user is a superadmin
@param GlobalPermissions the server-wide permissions
@param DatabasePermissions the access per database
@param CollectionPermissions the access per `database|collection`
@param ScriptPermissions the script access per database
@param OwnedDatabases the databases the user owns
@param Raw the row as the server sent it

## UserInfo(string, bool, IReadOnlyList<string>, Document, Document, Document, IReadOnlyList<string>, Document)
Creates the description.

@param Username the user name
@param IsAdmin whether the user is a superadmin
@param GlobalPermissions the server-wide permissions
@param DatabasePermissions the access per database
@param CollectionPermissions the access per `database|collection`
@param ScriptPermissions the script access per database
@param OwnedDatabases the databases the user owns
@param Raw the row as the server sent it

## CollectionPermissions
The access per `database|collection`.

## DatabasePermissions
The access per database.

## GlobalPermissions
The server-wide permissions.

## IsAdmin
Whether the user is a superadmin.

## OwnedDatabases
The databases the user owns.

## Raw
The row as the server sent it.

## ScriptPermissions
The script access per database.

## Username
The user name.
