package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.CreateUserRequest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class CreateUserRequestTest {
    @Test
    public void test_operation_type() {
        final var req = new CreateUserRequest();
        assertEquals(OperationType.CREATE_USER, req.getType());
    }

    @Test
    public void test_default_admin_is_false() {
        final var req = new CreateUserRequest();
        assertFalse(req.getAdmin());
    }

    @Test
    public void test_admin_setter_getter() {
        final var req = new CreateUserRequest();
        req.setAdmin(true);
        assertTrue(req.getAdmin());
    }

    @Test
    public void test_username_setter_getter() {
        final var req = new CreateUserRequest();
        req.setUsername("user123");
        assertEquals("user123", req.getUsername());
    }

    @Test
    public void test_password_setter_getter() {
        final var req = new CreateUserRequest();
        req.setPassword("pass123");
        assertEquals("pass123", req.getPassword());
    }

    @Test
    public void test_global_permissions_setter_getter() {
        final var req = new CreateUserRequest();
        final var perms = Set.of(GlobalPermissionType.CREATE_DATABASE);
        req.setGlobalPermissions(perms);
        assertEquals(perms, req.getGlobalPermissions());
    }

    @Test
    public void test_database_permissions_setter_getter() {
        final var req = new CreateUserRequest();
        final var perms = Map.of("db1", PermissionLevel.READ);
        req.setDatabasePermissions(perms);
        assertEquals(perms, req.getDatabasePermissions());
    }

    @Test
    public void test_collection_permissions_setter_getter() {
        final var req = new CreateUserRequest();
        final var perms = Map.of("db|coll", PermissionLevel.READ_WRITE);
        req.setCollectionPermissions(perms);
        assertEquals(perms, req.getCollectionPermissions());
    }

    @Test
    public void test_default_collections_are_empty() {
        final var req = new CreateUserRequest();
        assertNotNull(req.getGlobalPermissions());
        assertNotNull(req.getDatabasePermissions());
        assertNotNull(req.getCollectionPermissions());
        assertTrue(req.getGlobalPermissions().isEmpty());
        assertTrue(req.getDatabasePermissions().isEmpty());
        assertTrue(req.getCollectionPermissions().isEmpty());
    }
}
