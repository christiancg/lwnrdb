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
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SetPasswordResponse;

public class UserOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    public static OperationResponse processAuthenticate(AuthenticateRequest request, UUID clientId) {
        try {
            final var username = request.getUsername();
            final var password = request.getPassword();
            final var user = cache.getAdminUserEntry(username);

            if (user == null || !PasswordHasher.verify(password, user.getPasswordHash())) {
                return new OperationResponse(OperationType.AUTHENTICATE, ErrorCode.WRONG_CREDENTIALS);
            }

            clientTracker.setAuthenticatedUser(clientId, username);
            return new AuthenticateResponse("Authenticated");
        } catch (Exception e) {
            return new OperationResponse(OperationType.AUTHENTICATE, ErrorCode.AUTHENTICATION_ERROR);
        }
    }

    public static OperationResponse processCreateUser(CreateUserRequest request) {
        try {
            final var username = request.getUsername();
            if (cache.getAdminUserEntry(username) != null) {
                return new OperationResponse(OperationType.CREATE_USER, ErrorCode.USER_ALREADY_EXISTS);
            }

            final var passwordHash = PasswordHasher.hash(request.getPassword());
            final var userEntry = new AdminUserEntry(username, passwordHash, request.getAdmin(),
                    request.getGlobalPermissions(), request.getDatabasePermissions(),
                    request.getCollectionPermissions());

            AdminOperationHelper.saveUserEntry(userEntry);
            return new CreateUserResponse("User created successfully");
        } catch (IOException | InterruptedException e) {
            return new OperationResponse(OperationType.CREATE_USER, ErrorCode.ERROR_CREATING_USER);
        }
    }

    public static OperationResponse processDeleteUser(DeleteUserRequest request) {
        try {
            final var username = request.getUsername();
            final var user = cache.getAdminUserEntry(username);

            if (user == null) {
                return new OperationResponse(OperationType.DELETE_USER, ErrorCode.USER_NOT_FOUND);
            }

            if (user.isAdmin()
                    && cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count() == 1) {
                return new OperationResponse(OperationType.DELETE_USER, ErrorCode.CANNOT_DELETE_LAST_ADMIN);
            }

            AdminOperationHelper.deleteUserEntry(username);
            return new DeleteUserResponse("User deleted successfully");
        } catch (IOException | InterruptedException e) {
            return new OperationResponse(OperationType.DELETE_USER, ErrorCode.ERROR_DELETING_USER);
        }
    }

    public static OperationResponse processSetPassword(SetPasswordRequest request, UUID clientId) {
        try {
            final var callerUsername = clientTracker.getAuthenticatedUsername(clientId);
            final var targetUsername = request.getUsername();
            final var caller = cache.getAdminUserEntry(callerUsername);
            final var target = cache.getAdminUserEntry(targetUsername);

            if (target == null) {
                return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.USER_NOT_FOUND);
            }

            if (!caller.isAdmin()) {
                if (!callerUsername.equals(targetUsername)) {
                    return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.NO_PERMISSIONS);
                }
                if (!PasswordHasher.verify(request.getCurrentPassword(), target.getPasswordHash())) {
                    return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.CURRENT_PASSWORD_INCORRECT);
                }
            }

            final var newHash = PasswordHasher.hash(request.getNewPassword());
            final var updated = new AdminUserEntry(target.get_id(), newHash, target.isAdmin(),
                    target.getGlobalPermissions(), target.getDatabasePermissions(), target.getCollectionPermissions());
            AdminOperationHelper.saveUserEntry(updated);
            return new SetPasswordResponse("Password changed successfully");
        } catch (IOException | InterruptedException e) {
            return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.ERROR_CHANGING_PASSWORD);
        }
    }

    public static OperationResponse processChangePermissions(ChangePermissionsRequest request) {
        try {
            final var username = request.getUsername();
            final var user = cache.getAdminUserEntry(username);

            if (user == null) {
                return new OperationResponse(OperationType.CHANGE_PERMISSIONS, ErrorCode.USER_NOT_FOUND);
            }

            final var wasAdmin = user.isAdmin();
            final var isBecomingAdmin = request.getAdmin();

            if (wasAdmin && !isBecomingAdmin
                    && cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count() == 1) {
                return new OperationResponse(OperationType.CHANGE_PERMISSIONS, ErrorCode.CANNOT_DEMOTE_LAST_ADMIN);
            }

            final var updatedUser = new AdminUserEntry(username, user.getPasswordHash(), isBecomingAdmin,
                    request.getGlobalPermissions(), request.getDatabasePermissions(),
                    request.getCollectionPermissions());

            AdminOperationHelper.saveUserEntry(updatedUser);
            return new ChangePermissionsResponse("Permissions changed successfully");
        } catch (IOException | InterruptedException e) {
            return new OperationResponse(OperationType.CHANGE_PERMISSIONS, ErrorCode.ERROR_CHANGING_PERMISSIONS);
        }
    }
}
