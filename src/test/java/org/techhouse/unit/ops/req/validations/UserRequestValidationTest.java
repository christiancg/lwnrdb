package org.techhouse.unit.ops.req.validations;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.*;
import org.techhouse.ops.req.validations.RequestValidator;

import static org.junit.jupiter.api.Assertions.*;

public class UserRequestValidationTest {
    @Test
    public void test_authenticate_requires_username() {
        final var req = new AuthenticateRequest();
        req.setUsername(null);
        req.setPassword("password");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_requires_password() {
        final var req = new AuthenticateRequest();
        req.setUsername("user");
        req.setPassword(null);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_validates_username_pattern() {
        final var req = new AuthenticateRequest();
        req.setUsername("ab"); // too short
        req.setPassword("password");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_authenticate_valid_request() {
        final var req = new AuthenticateRequest();
        req.setUsername("validuser");
        req.setPassword("password");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_requires_long_password() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("short"); // less than 8 chars
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_admin_db() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.getDatabasePermissions().put("admin", org.techhouse.data.auth.PermissionLevel.READ);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_rejects_bad_collection_key_format() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.getCollectionPermissions().put("invalidkey", org.techhouse.data.auth.PermissionLevel.READ);
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_create_user_valid_request() {
        final var req = new CreateUserRequest();
        req.setUsername("user");
        req.setPassword("password123");
        req.getGlobalPermissions().add(org.techhouse.data.auth.GlobalPermissionType.CREATE_DATABASE);
        req.getDatabasePermissions().put("mydb", org.techhouse.data.auth.PermissionLevel.READ_WRITE);
        req.getCollectionPermissions().put("mydb|coll", org.techhouse.data.auth.PermissionLevel.READ);
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_delete_user_validates_username() {
        final var req = new DeleteUserRequest();
        req.setUsername("ab");
        assertFalse(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_delete_user_valid() {
        final var req = new DeleteUserRequest();
        req.setUsername("validuser");
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_valid() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.setAdmin(true);
        assertTrue(RequestValidator.validate(req).isValid());
    }

    @Test
    public void test_change_permissions_rejects_admin_db() {
        final var req = new ChangePermissionsRequest();
        req.setUsername("user");
        req.getDatabasePermissions().put("admin", org.techhouse.data.auth.PermissionLevel.READ);
        assertFalse(RequestValidator.validate(req).isValid());
    }
}
