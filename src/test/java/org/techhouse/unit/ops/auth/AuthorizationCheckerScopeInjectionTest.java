package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.auth.AuthorizationChecker;
import org.techhouse.ops.req.RequestParser;

public class AuthorizationCheckerScopeInjectionTest {
    private static final String DB = "scopeDb";
    private static final String GRANTED = "grantedColl";

    private static AdminUserEntry user(Map<String, PermissionLevel> databaseGrants,
            Map<String, PermissionLevel> collectionGrants) {
        return new AdminUserEntry("scope_user", "h", false, Set.of(), new HashMap<>(databaseGrants),
                new HashMap<>(collectionGrants));
    }

    private static AdminUserEntry collectionOnlyReader() {
        return user(Map.of(), Map.of(DB + "|" + GRANTED, PermissionLevel.READ));
    }

    private static boolean allowed(String requestJson, AdminUserEntry user) {
        return AuthorizationChecker.check(RequestParser.parseRequest(requestJson), user).isAllowed();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST_PROCEDURES", "LIST_SCHEDULES", "LIST_COLLECTIONS"})
    public void test_a_database_wide_list_ignores_an_injected_collection_name(String type) {
        final var injected = "{\"type\":\"" + type + "\",\"databaseName\":\"" + DB + "\",\"collectionName\":\""
                + GRANTED + "\",\"includeSource\":true}";
        assertFalse(allowed(injected, collectionOnlyReader()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST_PROCEDURES", "LIST_SCHEDULES", "LIST_COLLECTIONS"})
    public void test_a_database_wide_list_is_allowed_with_a_database_grant(String type) {
        final var request = "{\"type\":\"" + type + "\",\"databaseName\":\"" + DB + "\"}";
        assertTrue(allowed(request, user(Map.of(DB, PermissionLevel.READ), Map.of())));
    }

    @Test
    public void test_list_triggers_still_honours_its_optional_collection() {
        final var request = "{\"type\":\"LIST_TRIGGERS\",\"databaseName\":\"" + DB + "\",\"collectionName\":\""
                + GRANTED + "\"}";
        assertTrue(allowed(request, collectionOnlyReader()));
        assertFalse(allowed("{\"type\":\"LIST_TRIGGERS\",\"databaseName\":\"" + DB + "\"}", collectionOnlyReader()));
    }

    @Test
    public void test_a_collection_scoped_read_still_uses_the_collection_grant() {
        final var request = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + DB + "\",\"collectionName\":\"" + GRANTED
                + "\",\"_id\":\"a\"}";
        assertTrue(allowed(request, collectionOnlyReader()));
    }

    @Test
    public void test_every_operation_type_declares_a_scope() {
        for (final var type : OperationType.values()) {
            assertNotNull(type.scope(), type.name());
        }
        assertEquals(OperationType.Scope.DATABASE, OperationType.LIST_PROCEDURES.scope());
        assertEquals(OperationType.Scope.OPTIONAL_COLLECTION, OperationType.LIST_TRIGGERS.scope());
        assertEquals(OperationType.Scope.COLLECTION, OperationType.SAVE.scope());
        assertEquals(OperationType.Scope.NONE, OperationType.AUTHENTICATE.scope());
    }

    @Test
    public void test_may_write_follows_admin_and_collection_grants() {
        assertFalse(AuthorizationChecker.mayWrite(null, DB, GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(
                new AdminUserEntry("root", "h", true, Set.of(), new HashMap<>(), new HashMap<>()), DB, GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(user(Map.of(), Map.of(DB + "|" + GRANTED, PermissionLevel.READ_WRITE)),
                DB, GRANTED));
        assertFalse(AuthorizationChecker.mayWrite(collectionOnlyReader(), DB, GRANTED));
        assertFalse(AuthorizationChecker.mayWrite(
                user(Map.of(DB, PermissionLevel.READ_WRITE), Map.of(DB + "|" + GRANTED, PermissionLevel.READ)), DB,
                GRANTED));
        assertTrue(AuthorizationChecker.mayWrite(user(Map.of(DB, PermissionLevel.READ_WRITE), Map.of()), DB, "other"));
    }
}
