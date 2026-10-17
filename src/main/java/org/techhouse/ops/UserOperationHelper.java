package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.GrantPruner;
import org.techhouse.ops.admin.GrantRewrite;
import org.techhouse.ops.admin.RestampedRecords;
import org.techhouse.ops.auth.AuthFailureThrottle;
import org.techhouse.ops.auth.PasswordCheckGate;
import org.techhouse.ops.req.AuthenticateRequest;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.SetPasswordRequest;
import org.techhouse.ops.resp.OperationResponse;

public class UserOperationHelper {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final AuthFailureThrottle authFailureThrottle = IocContainer.get(AuthFailureThrottle.class);
    private static final PasswordCheckGate passwordCheckGate = IocContainer.get(PasswordCheckGate.class);
    private static final Configuration configuration = Configuration.getInstance();

    public static Map<String, List<String>> ownedDatabasesByUser(Collection<AdminDbEntry> databases) {
        final var ownedByUser = new HashMap<String, List<String>>();
        for (final var database : databases) {
            for (final var owner : database.getOwners()) {
                ownedByUser.computeIfAbsent(owner, _ -> new ArrayList<>()).add(database.get_id());
            }
        }
        return ownedByUser;
    }

    public static OperationResponse processAuthenticate(AuthenticateRequest request, UUID clientId) {
        return OperationResponse.respondOrError(OperationType.AUTHENTICATE, ErrorCode.AUTHENTICATION_ERROR, () -> {
            final var username = request.getUsername();
            final var password = request.getPassword();
            final var address = clientTracker.addressOf(clientId);
            final var key = AuthFailureThrottle.keyOf(address, username);
            pauseFor(key, AuthFailureThrottle.addressKeyOf(address));
            final var user = cache.getAdminUserEntry(username);
            final boolean verified = passwordCheckGate.run(() -> user == null
                    ? PasswordHasher.dummyVerify(password)
                    : PasswordHasher.verify(password, user.getPasswordHash()));
            if (!verified) {
                return failedAttempt(key, address, clientId);
            }
            final var verifiedHash = user.getPasswordHash();
            final var bound = AdminOperationHelper.withUsersLock(() -> {
                final var current = cache.getAdminUserEntry(username);
                if (current == null || !verifiedHash.equals(current.getPasswordHash())) {
                    return null;
                }
                clientTracker.setAuthenticatedUser(clientId, username);
                return OperationResponse.ok(OperationType.AUTHENTICATE, "Authenticated");
            });
            if (bound == null) {
                return failedAttempt(key, address, clientId);
            }
            authFailureThrottle.recordSuccess(key);
            return bound;
        });
    }

    private static void pauseFor(String key, String addressKey) throws InterruptedException {
        final var delay = Math.max(authFailureThrottle.reserve(key), authFailureThrottle.reserve(addressKey));
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private static OperationResponse failedAttempt(String key, String address, UUID clientId) {
        authFailureThrottle.recordFailure(key);
        authFailureThrottle.recordAddressFailure(address);
        final var failures = clientTracker.recordFailedAuthentication(clientId);
        final var cap = configuration.getMaxFailedAuthAttempts();
        if (cap > 0 && failures >= cap) {
            return new OperationResponse(OperationType.AUTHENTICATE, ErrorCode.TOO_MANY_FAILED_ATTEMPTS);
        }
        return new OperationResponse(OperationType.AUTHENTICATE, ErrorCode.WRONG_CREDENTIALS);
    }

    public static OperationResponse processCreateUser(CreateUserRequest request) {
        final var passwordHash = PasswordHasher.hash(request.getPassword());
        return OperationResponse.respondOrError(OperationType.CREATE_USER, ErrorCode.ERROR_CREATING_USER,
                () -> AdminOperationHelper.withUsersLock(() -> {
                    final var username = request.getUsername();
                    if (cache.getAdminUserEntry(username) != null) {
                        return new OperationResponse(OperationType.CREATE_USER, ErrorCode.USER_ALREADY_EXISTS);
                    }
                    AdminOperationHelper.saveUserEntry(new AdminUserEntry(username, passwordHash, request.getAdmin(),
                            request.getGlobalPermissions(), request.getDatabasePermissions(),
                            request.getCollectionPermissions(), request.getScriptPermissions()));
                    return OperationResponse.ok(OperationType.CREATE_USER, "User created successfully");
                }));
    }

    public static OperationResponse processDeleteUser(DeleteUserRequest request, RestampedRecords restamped) {
        return OperationResponse.respondOrError(OperationType.DELETE_USER, ErrorCode.ERROR_DELETING_USER,
                () -> AdminOperationHelper.withUsersLock(() -> {
                    final var username = request.getUsername();
                    final var user = cache.getAdminUserEntry(username);
                    if (user == null) {
                        return new OperationResponse(OperationType.DELETE_USER, ErrorCode.USER_NOT_FOUND);
                    }
                    if (user.isAdmin() && isLastAdmin()) {
                        return new OperationResponse(OperationType.DELETE_USER, ErrorCode.CANNOT_DELETE_LAST_ADMIN);
                    }
                    AdminOperationHelper.deleteUserEntry(username);
                    AdminStamp.tombstone(AdminRecordKey.user(username));
                    GrantPruner.forDeletedUser(username, new GrantRewrite.Restamp(restamped));
                    return OperationResponse.ok(OperationType.DELETE_USER, "User deleted successfully");
                }));
    }

    public static OperationResponse processSetPassword(SetPasswordRequest request, UUID clientId) {
        return OperationResponse.respondOrError(OperationType.SET_PASSWORD, ErrorCode.ERROR_CHANGING_PASSWORD, () -> {
            final var callerUsername = clientTracker.getAuthenticatedUsername(clientId);
            final var targetUsername = request.getUsername();
            final var caller = cache.getAdminUserEntry(callerUsername);
            if (!caller.isAdmin() && !callerUsername.equals(targetUsername)) {
                return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.NO_PERMISSIONS);
            }
            final var target = cache.getAdminUserEntry(targetUsername);
            if (target == null) {
                return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.USER_NOT_FOUND);
            }
            final var verifiedHash = target.getPasswordHash();
            if (!caller.isAdmin()) {
                final boolean current = passwordCheckGate
                        .run(() -> PasswordHasher.verify(request.getCurrentPassword(), verifiedHash));
                if (!current) {
                    return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.CURRENT_PASSWORD_INCORRECT);
                }
            }
            final var newHash = passwordCheckGate.run(() -> PasswordHasher.hash(request.getNewPassword()));
            final var keepClientId = targetUsername.equals(callerUsername) ? clientId : null;
            return AdminOperationHelper.withUsersLock(
                    () -> savePassword(targetUsername, newHash, caller.isAdmin() ? null : verifiedHash, keepClientId));
        });
    }

    private static OperationResponse savePassword(String username, String newHash, String requiredCurrentHash,
            UUID keepClientId) throws IOException, InterruptedException {
        final var current = cache.getAdminUserEntry(username);
        if (current == null) {
            return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.USER_NOT_FOUND);
        }
        if (requiredCurrentHash != null && !requiredCurrentHash.equals(current.getPasswordHash())) {
            return new OperationResponse(OperationType.SET_PASSWORD, ErrorCode.CURRENT_PASSWORD_INCORRECT);
        }
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(current.get_id(), newHash, current.isAdmin(),
                current.getGlobalPermissions(), current.getDatabasePermissions(), current.getCollectionPermissions(),
                current.getScriptPermissions()));
        clientTracker.deauthenticateUserExcept(username, keepClientId);
        return OperationResponse.ok(OperationType.SET_PASSWORD, "Password changed successfully");
    }

    public static OperationResponse processChangePermissions(ChangePermissionsRequest request) {
        return OperationResponse.respondOrError(OperationType.CHANGE_PERMISSIONS, ErrorCode.ERROR_CHANGING_PERMISSIONS,
                () -> AdminOperationHelper.withUsersLock(() -> {
                    final var username = request.getUsername();
                    final var user = cache.getAdminUserEntry(username);
                    if (user == null) {
                        return new OperationResponse(OperationType.CHANGE_PERMISSIONS, ErrorCode.USER_NOT_FOUND);
                    }
                    final var isBecomingAdmin = request.getAdmin();
                    if (user.isAdmin() && !isBecomingAdmin && isLastAdmin()) {
                        return new OperationResponse(OperationType.CHANGE_PERMISSIONS,
                                ErrorCode.CANNOT_DEMOTE_LAST_ADMIN);
                    }
                    AdminOperationHelper.saveUserEntry(new AdminUserEntry(username, user.getPasswordHash(),
                            isBecomingAdmin, request.getGlobalPermissions(), request.getDatabasePermissions(),
                            request.getCollectionPermissions(), request.getScriptPermissions()));
                    return OperationResponse.ok(OperationType.CHANGE_PERMISSIONS, "Permissions changed successfully");
                }));
    }

    private static boolean isLastAdmin() {
        return cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count() == 1;
    }
}
