# AsyncUsers

User management. Admin only, except a user changing their own password through the client.

The sync class `Users` has the same members; its methods block and return the value directly.

## create

Creates a user.

Args:
    username: the new user's name
    password: the new user's password
    permissions: what the user may do; nothing beyond logging in when omitted

## delete

Deletes a user.

## change_permissions

Replaces a user's permissions.

## set_password

Sets another user's password.

## list

Lists the users.

Args:
    filter: keeps only the users that match, for example `field("admin").eq(True)`
