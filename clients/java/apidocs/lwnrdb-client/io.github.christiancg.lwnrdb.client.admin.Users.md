User and permission management. Admin only.

## create(String, String)

Creates a user with no permissions.

@param username 3-64 characters from `A-Z a-z 0-9 _ -`
@param password the password

## create(String, String, Permissions)

@param username 3-64 characters from `A-Z a-z 0-9 _ -`
@param password the password
@param permissions what the user may do

## delete(String)

@param username the user; the last admin cannot be deleted

## changePermissions(String, Permissions)

Replaces every permission of a user.

@param username the user
@param permissions the new permissions

## setPassword(String, String)

Sets another user's password without the current one.

@param username the user
@param newPassword the new password

## list()

@return every user, without password hashes

## list(Filter)

@param filter keeps the users it matches
@return the matching users
