package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.data.auth.ScriptPermissionLevel;

public class AdminUserEntryTest {
    @Test
    public void test_constructor_sets_admin_db_and_users_collection() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertEquals(Globals.ADMIN_DB_NAME, entry.getDatabaseName());
        assertEquals(Globals.ADMIN_USERS_COLLECTION_NAME, entry.getCollectionName());
        assertEquals("test_user", entry.get_id());
    }

    @Test
    public void test_serialises_to_json_with_all_fields() {
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.CREATE_DATABASE);
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("mydb", PermissionLevel.READ_WRITE);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("mydb|coll", PermissionLevel.READ);

        final var entry = new AdminUserEntry("test_user", "hash", true, globalPerms, dbPerms, collPerms);
        final var data = entry.getData();

        assertNotNull(data.get("_id"));
        assertNotNull(data.get("passwordHash"));
        assertTrue(data.get("admin").asJsonBoolean().getValue());
        assertNotNull(data.get("globalPermissions"));
        assertNotNull(data.get("databasePermissions"));
        assertNotNull(data.get("collectionPermissions"));
    }

    @Test
    public void test_from_json_object_round_trip() {
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.DROP_DATABASE);
        final var dbPerms = new HashMap<String, PermissionLevel>();
        dbPerms.put("mydb", PermissionLevel.READ);
        final var collPerms = new HashMap<String, PermissionLevel>();
        collPerms.put("mydb|coll", PermissionLevel.READ_WRITE);

        final var original = new AdminUserEntry("test_user", "hash123", true, globalPerms, dbPerms, collPerms);
        final var restored = AdminUserEntry.fromJsonObject(original.getData());

        assertEquals(original.get_id(), restored.get_id());
        assertEquals(original.getPasswordHash(), restored.getPasswordHash());
        assertEquals(original.isAdmin(), restored.isAdmin());
        assertEquals(original.getGlobalPermissions(), restored.getGlobalPermissions());
        assertEquals(original.getDatabasePermissions(), restored.getDatabasePermissions());
        assertEquals(original.getCollectionPermissions(), restored.getCollectionPermissions());
    }

    @Test
    public void test_setters_rebuild_data() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        entry.setAdmin(true);
        assertTrue(entry.isAdmin());
        assertTrue(entry.getData().get("admin").asJsonBoolean().getValue());

        final var newPerms = new HashSet<GlobalPermissionType>();
        newPerms.add(GlobalPermissionType.CREATE_DATABASE);
        entry.setGlobalPermissions(newPerms);
        assertEquals(newPerms, entry.getGlobalPermissions());
    }

    @Test
    public void test_equals_hashcode_toString() {
        final var entry1 = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        final var entry2 = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());

        assertEquals(entry1, entry2);
        assertEquals(entry1.hashCode(), entry2.hashCode());
        assertNotNull(entry1.toString());
        assertTrue(entry1.toString().contains("test_user"));
    }

    @Test
    public void test_script_permissions_round_trip() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("mydb", ScriptPermissionLevel.RUN);
        scriptPerms.put("otherdb", ScriptPermissionLevel.NONE);
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), scriptPerms);

        assertEquals("RUN",
                entry.getData().get("scriptPermissions").asJsonObject().get("mydb").asJsonString().getValue());
        final var parsed = AdminUserEntry.fromJsonObject(entry.getData());
        assertEquals(scriptPerms, parsed.getScriptPermissions());
        assertTrue(parsed.canRunScripts("mydb"));
        assertFalse(parsed.canRunScripts("otherdb"));
        assertFalse(parsed.canRunScripts("unknowndb"));
    }

    @Test
    public void test_script_permissions_default_to_empty() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertTrue(entry.getScriptPermissions().isEmpty());
        assertFalse(entry.canRunScripts("mydb"));
    }

    @Test
    public void test_null_script_permissions_is_treated_as_empty() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), null);
        assertTrue(entry.getScriptPermissions().isEmpty());
    }

    // Records written before per-database script permissions existed must still load
    @Test
    public void test_from_json_object_without_script_permissions_field() {
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        final var legacy = entry.getData();
        legacy.remove("scriptPermissions");
        final var parsed = AdminUserEntry.fromJsonObject(legacy);
        assertTrue(parsed.getScriptPermissions().isEmpty());
        assertFalse(parsed.canRunScripts("mydb"));
    }

    @Test
    public void test_response_json_exposes_script_permissions() {
        final var scriptPerms = new HashMap<String, ScriptPermissionLevel>();
        scriptPerms.put("mydb", ScriptPermissionLevel.MANAGE);
        final var entry = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), scriptPerms);
        final var response = entry.toResponseJson(java.util.List.of());
        assertEquals("MANAGE", response.get("scriptPermissions").asJsonObject().get("mydb").asJsonString().getValue());
    }

    @Test
    public void test_script_permissions_participate_in_equality() {
        final var granted = new HashMap<String, ScriptPermissionLevel>();
        granted.put("mydb", ScriptPermissionLevel.RUN);
        final var withGrant = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>(), granted);
        final var without = new AdminUserEntry("test_user", "hash", false, new HashSet<>(), new HashMap<>(),
                new HashMap<>());
        assertNotEquals(withGrant, without);
        assertTrue(withGrant.toString().contains("scriptPermissions"));
    }
}
