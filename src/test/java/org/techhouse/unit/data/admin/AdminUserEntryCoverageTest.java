package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;

public class AdminUserEntryCoverageTest {

    private static AdminUserEntry user(String name, String hash) {
        final Map<String, PermissionLevel> dbPerms = new HashMap<>();
        dbPerms.put("db1", PermissionLevel.READ_WRITE);
        return new AdminUserEntry(name, hash, false, Set.of(GlobalPermissionType.CREATE_DATABASE), dbPerms,
                new HashMap<>());
    }

    @Test
    public void test_equals_and_hashcode() {
        final var a = user("bob", "h");
        final var b = user("bob", "h");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not-a-user");
        assertNotEquals(a, user("carol", "h"));
    }

    @Test
    public void test_to_response_json_includes_permissions_and_owned_dbs() {
        final var json = user("bob", "h").toResponseJson(List.of("db1", "db2"));
        assertEquals("bob", json.get("_id").asJsonString().getValue());
        assertEquals(1, json.get("globalPermissions").asJsonArray().asList().size());
        assertEquals("READ_WRITE", json.get("databasePermissions").asJsonObject().get("db1").asJsonString().getValue());
        assertEquals(2, json.get("ownedDatabases").asJsonArray().asList().size());
    }

    @Test
    public void test_setters_rebuild_data_and_getters() {
        final var user = user("bob", "h");
        user.setAdmin(true);
        user.setGlobalPermissions(Set.of(GlobalPermissionType.DROP_DATABASE));
        assertTrue(user.isAdmin());
        assertEquals(Set.of(GlobalPermissionType.DROP_DATABASE), user.getGlobalPermissions());
        assertEquals(PermissionLevel.READ_WRITE, user.getDatabasePermissions().get("db1"));
        assertEquals("h", user.getPasswordHash());
        assertTrue(user.toString().contains("bob"));
    }
}
