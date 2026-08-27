package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.ChangePermissionsRequest;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.validations.RequestValidator;
import org.techhouse.test.TestUtils;

public class UserOperationHelperScriptPermissionTest {
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static CreateUserRequest createRequest() {
        final var request = new CreateUserRequest();
        request.setUsername("promoted");
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        return request;
    }

    // Backward compatibility over the wire: an existing client sending a boolean keeps working
    @Test
    public void test_create_user_accepts_boolean_script_permissions() {
        final var parsed = (CreateUserRequest) RequestParser
                .parseRequest("{\"type\":\"CREATE_USER\",\"username\":\"legacyclient\",\"password\":\"password123\","
                        + "\"scriptPermissions\":{\"mydb\":true}}");
        assertTrue(RequestValidator.validate(parsed).isValid());
        assertEquals(OperationStatus.OK, UserOperationHelper.processCreateUser(parsed).getStatus());
        final var stored = cache.getAdminUserEntry("legacyclient");
        assertTrue(stored.canRunScripts("mydb"));
        assertFalse(stored.canManageScripts("mydb"));
    }

    @Test
    public void test_create_user_accepts_level_strings() {
        final var parsed = (CreateUserRequest) RequestParser
                .parseRequest("{\"type\":\"CREATE_USER\",\"username\":\"levelclient\",\"password\":\"password123\","
                        + "\"scriptPermissions\":{\"mydb\":\"MANAGE\"}}");
        assertTrue(RequestValidator.validate(parsed).isValid());
        assertEquals(OperationStatus.OK, UserOperationHelper.processCreateUser(parsed).getStatus());
        assertTrue(cache.getAdminUserEntry("levelclient").canManageScripts("mydb"));
    }

    // A typo'd level must fail loudly rather than read as a denial nobody can explain
    @Test
    public void test_invalid_level_string_is_a_validation_error() {
        final var parsed = RequestParser
                .parseRequest("{\"type\":\"CREATE_USER\",\"username\":\"typoclient\",\"password\":\"password123\","
                        + "\"scriptPermissions\":{\"mydb\":\"MANAGER\"}}");
        final var validation = RequestValidator.validate(parsed);
        assertFalse(validation.isValid());
        assertTrue(validation.getErrorMessage().contains("MANAGE"), validation.getErrorMessage());
    }

    @Test
    public void test_change_permissions_can_promote_and_demote() {
        final var create = createRequest();
        final var initial = new HashMap<String, ScriptPermissionLevel>();
        initial.put("mydb", ScriptPermissionLevel.RUN);
        create.setScriptPermissions(initial);
        UserOperationHelper.processCreateUser(create);
        assertFalse(cache.getAdminUserEntry("promoted").canManageScripts("mydb"));

        final var promote = new ChangePermissionsRequest();
        promote.setUsername("promoted");
        promote.setAdmin(false);
        promote.setGlobalPermissions(new HashSet<>());
        promote.setDatabasePermissions(new HashMap<>());
        promote.setCollectionPermissions(new HashMap<>());
        final var manage = new HashMap<String, ScriptPermissionLevel>();
        manage.put("mydb", ScriptPermissionLevel.MANAGE);
        promote.setScriptPermissions(manage);
        assertEquals(OperationStatus.OK, UserOperationHelper.processChangePermissions(promote).getStatus());
        assertTrue(cache.getAdminUserEntry("promoted").canManageScripts("mydb"));

        final var demote = new ChangePermissionsRequest();
        demote.setUsername("promoted");
        demote.setAdmin(false);
        demote.setGlobalPermissions(new HashSet<>());
        demote.setDatabasePermissions(new HashMap<>());
        demote.setCollectionPermissions(new HashMap<>());
        final var none = new HashMap<String, ScriptPermissionLevel>();
        none.put("mydb", ScriptPermissionLevel.NONE);
        demote.setScriptPermissions(none);
        assertEquals(OperationStatus.OK, UserOperationHelper.processChangePermissions(demote).getStatus());
        assertFalse(cache.getAdminUserEntry("promoted").canRunScripts("mydb"));
    }

    @Test
    public void test_reserved_database_in_script_permissions_is_rejected() {
        final var parsed = RequestParser
                .parseRequest("{\"type\":\"CREATE_USER\",\"username\":\"reservedclient\",\"password\":\"password123\","
                        + "\"scriptPermissions\":{\"admin\":\"MANAGE\"}}");
        assertFalse(RequestValidator.validate(parsed).isValid());
        assertNull(ErrorCode.byCode("nonexistent"));
    }
}
