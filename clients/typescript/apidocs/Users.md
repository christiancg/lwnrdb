# Users

User management. Admin only, except a user changing its own password.

## create

@param username the new user's name
@param password its password
@param permissions optional grants; none by default

## delete

@param username the user to delete; the last admin cannot be deleted

## changePermissions

Replaces a user's grants.

@param username the user
@param permissions the new grants

## setPassword

Sets another user's password.

@param username the user
@param newPassword the new password

## list

@param filter optional filter over the user rows
@returns the users; an empty listing is an empty array
