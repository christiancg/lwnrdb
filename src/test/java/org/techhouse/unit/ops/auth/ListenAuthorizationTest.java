package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.StopListenRequest;

public class ListenAuthorizationTest {

    private AdminUserEntry adminUser() {
        return new AdminUserEntry("admin", "hash", true, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry noPermsUser() {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>());
    }

    private AdminUserEntry readUser() {
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("db" + "|" + "coll", PermissionLevel.READ);
        return new AdminUserEntry("reader", "hash", false, new HashSet<>(), new HashMap<>(), collPerms);
    }

    // Admin can LISTEN on any collection
    @Test
    public void listen_admin_isAllowed() {
        final var req = listenRequest();

        assertTrue(AuthorizationChecker.check(req, adminUser()).isAllowed());
    }

    // User without READ permission is denied
    @Test
    public void listen_noPermissions_isDenied() {
        final var req = listenRequest();

        assertFalse(AuthorizationChecker.check(req, noPermsUser()).isAllowed());
    }

    // User with READ on the collection is allowed
    @Test
    public void listen_withReadPermission_isAllowed() {
        final var req = listenRequest();

        assertTrue(AuthorizationChecker.check(req, readUser()).isAllowed());
    }

    // STOP_LISTEN is free for any authenticated user
    @Test
    public void stopListen_noPermissions_isAllowed() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(AuthorizationChecker.check(req, noPermsUser()).isAllowed());
    }

    // STOP_LISTEN is allowed for admin too
    @Test
    public void stopListen_admin_isAllowed() {
        final var req = new StopListenRequest();
        req.setListenId(UUID.randomUUID().toString());

        assertTrue(AuthorizationChecker.check(req, adminUser()).isAllowed());
    }

    private static ListenRequest listenRequest() {
        final var req = new ListenRequest("db", "coll");
        req.setAggregationSteps(List.of());
        return req;
    }
}
