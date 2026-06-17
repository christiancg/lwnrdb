package org.techhouse.ops;

import java.io.IOException;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.AuthenticateResponse;
import org.techhouse.ops.resp.ChangePermissionsResponse;
import org.techhouse.ops.resp.CreateUserResponse;
import org.techhouse.ops.resp.DeleteUserResponse;
import org.techhouse.ops.resp.SetPasswordResponse;

public class UserOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    public static AuthenticateResponse processAuthenticate(AuthenticateRequest request, UUID clientId) {
        try {
            final var username = request.getUsername();
            final var password = request.getPassword();
            final var user = cache.getAdminUserEntry(username);

            if (user == null || !PasswordHasher.verify(password, user.getPasswordHash())) {
                return new AuthenticateResponse(OperationStatus.ERROR,
                        "The user doesn't exist or the wrong credentials have been provided");
            }

            clientTracker.setAuthenticatedUser(clientId, username);
            return new AuthenticateResponse(OperationStatus.OK, "Authenticated");
        } catch (Exception e) {
            return new AuthenticateResponse(OperationStatus.ERROR, "Error during authentication: " + e.getMessage());
        }
    }

    public static CreateUserResponse processCreateUser(CreateUserRequest request) {
        try {
            final var username = request.getUsername();
            if (cache.getAdminUserEntry(username) != null) {
                return new CreateUserResponse(OperationStatus.ERROR, "User already exists");
            }

            final var passwordHash = PasswordHasher.hash(request.getPassword());
            final var userEntry = new AdminUserEntry(username, passwordHash, request.getAdmin(),
                    request.getGlobalPermissions(), request.getDatabasePermissions(),
                    request.getCollectionPermissions());

            AdminOperationHelper.saveUserEntry(userEntry);
            return new CreateUserResponse(OperationStatus.OK, "User created successfully");
        } catch (IOException | InterruptedException e) {
            return new CreateUserResponse(OperationStatus.ERROR, "Error creating user: " + e.getMessage());
        }
    }

    public static DeleteUserResponse processDeleteUser(DeleteUserRequest request) {
        try {
            final var username = request.getUsername();
            final var user = cache.getAdminUserEntry(username);

            if (user == null) {
                return new DeleteUserResponse(OperationStatus.NOT_FOUND, "User not found");
            }

            if (user.isAdmin()
                    && cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count() == 1) {
                return new DeleteUserResponse(OperationStatus.ERROR, "Cannot delete the last admin user");
            }

            AdminOperationHelper.deleteUserEntry(username);
            return new DeleteUserResponse(OperationStatus.OK, "User deleted successfully");
        } catch (IOException | InterruptedException e) {
            return new DeleteUserResponse(OperationStatus.ERROR, "Error deleting user: " + e.getMessage());
        }
    }

    public static SetPasswordResponse processSetPassword(SetPasswordRequest request, UUID clientId) {
        try {
            final var callerUsername = clientTracker.getAuthenticatedUsername(clientId);
            final var targetUsername = request.getUsername();
            final var caller = cache.getAdminUserEntry(callerUsername);
            final var target = cache.getAdminUserEntry(targetUsername);

            if (target == null) {
                return new SetPasswordResponse(OperationStatus.NOT_FOUND, "User not found");
            }

            if (!caller.isAdmin()) {
                if (!callerUsername.equals(targetUsername)) {
                    return new SetPasswordResponse(OperationStatus.FORBIDDEN, "action is forbidden, no permissions");
                }
                if (!PasswordHasher.verify(request.getCurrentPassword(), target.getPasswordHash())) {
                    return new SetPasswordResponse(OperationStatus.ERROR, "Current password is incorrect");
                }
            }

            final var newHash = PasswordHasher.hash(request.getNewPassword());
            final var updated = new AdminUserEntry(target.get_id(), newHash, target.isAdmin(),
                    target.getGlobalPermissions(), target.getDatabasePermissions(), target.getCollectionPermissions());
            AdminOperationHelper.saveUserEntry(updated);
            return new SetPasswordResponse(OperationStatus.OK, "Password changed successfully");
        } catch (IOException | InterruptedException e) {
            return new SetPasswordResponse(OperationStatus.ERROR, "Error changing password: " + e.getMessage());
        }
    }

    public static ChangePermissionsResponse processChangePermissions(ChangePermissionsRequest request) {
        try {
            final var username = request.getUsername();
            final var user = cache.getAdminUserEntry(username);

            if (user == null) {
                return new ChangePermissionsResponse(OperationStatus.NOT_FOUND, "User not found");
            }

            final var wasAdmin = user.isAdmin();
            final var isBecomingAdmin = request.getAdmin();

            if (wasAdmin && !isBecomingAdmin
                    && cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count() == 1) {
                return new ChangePermissionsResponse(OperationStatus.ERROR, "Cannot demote the last admin user");
            }

            final var updatedUser = new AdminUserEntry(username, user.getPasswordHash(), isBecomingAdmin,
                    request.getGlobalPermissions(), request.getDatabasePermissions(),
                    request.getCollectionPermissions());

            AdminOperationHelper.saveUserEntry(updatedUser);
            return new ChangePermissionsResponse(OperationStatus.OK, "Permissions changed successfully");
        } catch (IOException | InterruptedException e) {
            return new ChangePermissionsResponse(OperationStatus.ERROR,
                    "Error changing permissions: " + e.getMessage());
        }
    }
}
