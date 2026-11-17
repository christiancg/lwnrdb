User and permission management. Obtained from `Admin.Users()`. Admin only.

## ChangePermissionsAsync(string, Permissions, CancellationToken)
Replaces every permission of a user.

@param username the user
@param permissions the new permissions
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-5` when it would demote the last admin

## CreateAsync(string, string, Permissions, CancellationToken)
Creates a user.

@param username 3 to 64 characters from `A-Z a-z 0-9 _ -`
@param password the password
@param permissions what the user may do, or null for none
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.ConflictException with code `409-1` when the user already exists

## DeleteAsync(string, CancellationToken)
Deletes a user.

@param username the user; the last admin cannot be deleted
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-4` when the user is the last admin
@exception Lwnrdb.Client.Errors.NotFoundException with code `404-1` when the user does not exist

## ListAsync(Filter, CancellationToken)
Lists users, without their password hashes.

@param filter keeps the users it matches, or null for every user
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the users; empty when none matches

## SetPasswordAsync(string, string, CancellationToken)
Sets another user's password without the current one.

@param username the user
@param newPassword the new password
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
