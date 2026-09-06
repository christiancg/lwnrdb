package org.techhouse.unit.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class AdminUserEntryScriptPermissionTest {
    private static AdminUserEntry entryWith(HashMap<String, ScriptPermissionLevel> scriptPermissions) {
        return new AdminUserEntry("user", "hash", false, new HashSet<>(), new HashMap<>(), new HashMap<>(),
                scriptPermissions);
    }

    private static JsonObject recordWithRawScriptPermissions(JsonObject rawScriptPermissions) {
        final var data = entryWith(new HashMap<>()).getData();
        data.add("scriptPermissions", rawScriptPermissions);
        return data;
    }

    @Test
    public void test_legacy_boolean_true_reads_as_run() {
        final var raw = new JsonObject();
        raw.add("mydb", new JsonBoolean(true));
        final var parsed = AdminUserEntry.fromJsonObject(recordWithRawScriptPermissions(raw));
        assertEquals(ScriptPermissionLevel.RUN, parsed.getScriptPermissions().get("mydb"));
        assertTrue(parsed.canRunScripts("mydb"));
        assertFalse(parsed.canManageScripts("mydb"));
    }

    @Test
    public void test_legacy_boolean_false_reads_as_none() {
        final var raw = new JsonObject();
        raw.add("mydb", new JsonBoolean(false));
        final var parsed = AdminUserEntry.fromJsonObject(recordWithRawScriptPermissions(raw));
        assertEquals(ScriptPermissionLevel.NONE, parsed.getScriptPermissions().get("mydb"));
        assertFalse(parsed.canRunScripts("mydb"));
    }

    @Test
    public void test_absent_database_reads_as_none() {
        final var entry = entryWith(new HashMap<>());
        assertFalse(entry.canRunScripts("mydb"));
        assertFalse(entry.canManageScripts("mydb"));
    }

    @Test
    public void test_level_string_round_trips() {
        final var grants = new HashMap<String, ScriptPermissionLevel>();
        grants.put("mydb", ScriptPermissionLevel.MANAGE);
        grants.put("otherdb", ScriptPermissionLevel.RUN);
        final var entry = entryWith(grants);
        final var parsed = AdminUserEntry.fromJsonObject(entry.getData());
        assertEquals(grants, parsed.getScriptPermissions());
        assertTrue(parsed.canManageScripts("mydb"));
        assertTrue(parsed.canRunScripts("otherdb"));
        assertFalse(parsed.canManageScripts("otherdb"));
    }

    // A record must never be read as more permissive than it says
    @Test
    public void test_unrecognised_level_reads_as_none() {
        final var raw = new JsonObject();
        raw.add("mydb", new JsonString("MANAGER"));
        final var parsed = AdminUserEntry.fromJsonObject(recordWithRawScriptPermissions(raw));
        assertEquals(ScriptPermissionLevel.NONE, parsed.getScriptPermissions().get("mydb"));
        assertFalse(parsed.canRunScripts("mydb"));
    }

    // The mixed-version hazard, pinned: a record read from the legacy boolean form is persisted as the
    // string form on its next write, which a node without ScriptPermissionLevel cannot parse.
    @Test
    public void test_rebuild_data_writes_the_string_form() {
        final var raw = new JsonObject();
        raw.add("mydb", new JsonBoolean(true));
        final var parsed = AdminUserEntry.fromJsonObject(recordWithRawScriptPermissions(raw));
        parsed.setAdmin(false);
        assertEquals("RUN",
                parsed.getData().get("scriptPermissions").asJsonObject().get("mydb").asJsonString().getValue());
    }

    // The existing RUN_SCRIPT grant keeps working verbatim
    @Test
    public void test_can_run_scripts_unchanged_for_legacy_records() {
        final var raw = new JsonObject();
        raw.add("granted", new JsonBoolean(true));
        raw.add("revoked", new JsonBoolean(false));
        final var parsed = AdminUserEntry.fromJsonObject(recordWithRawScriptPermissions(raw));
        assertTrue(parsed.canRunScripts("granted"));
        assertFalse(parsed.canRunScripts("revoked"));
        assertFalse(parsed.canRunScripts("unknown"));
    }

    @Test
    public void test_can_manage_scripts_requires_manage() {
        final var grants = new HashMap<String, ScriptPermissionLevel>();
        grants.put("none", ScriptPermissionLevel.NONE);
        grants.put("run", ScriptPermissionLevel.RUN);
        grants.put("manage", ScriptPermissionLevel.MANAGE);
        final var entry = entryWith(grants);
        assertFalse(entry.canManageScripts("none"));
        assertFalse(entry.canManageScripts("run"));
        assertTrue(entry.canManageScripts("manage"));
        assertTrue(entry.canRunScripts("manage"));
    }
}
