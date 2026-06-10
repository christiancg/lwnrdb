package org.techhouse.unit.ops;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.*;
import org.techhouse.test.TestUtils;

import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

public class UserOperationHelperLastAdminTest {

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        // Create exactly one admin user
        final var req = new CreateUserRequest();
        req.setUsername("soleadmin");
        req.setPassword("password123");
        req.setAdmin(true);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(req);
    }

    @AfterAll
    public static void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_delete_last_admin_refused() {
        final var req = new DeleteUserRequest();
        req.setUsername("soleadmin");
        final var resp = UserOperationHelper.processDeleteUser(req);
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("last admin"));
    }

    @Test
    public void test_demote_last_admin_refused() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("soleadmin");
        req.setAdmin(false);
        req.setGlobalPermissions(new HashSet<>());
        req.setDatabasePermissions(new HashMap<>());
        req.setCollectionPermissions(new HashMap<>());
        final var resp = UserOperationHelper.processChangePermissions(req);
        assertEquals(OperationStatus.ERROR, resp.getStatus());
        assertTrue(resp.getMessage().contains("last admin"));
    }
}
