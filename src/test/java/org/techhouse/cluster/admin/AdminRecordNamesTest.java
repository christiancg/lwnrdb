package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminRecordKey.Kind;

public class AdminRecordNamesTest {
    @Test
    public void databaseKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.database("shop")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.database("../peer_escape_marker")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.database("admin")));
        assertFalse(AdminRecordNames.accepts(new AdminRecordKey(Kind.DATABASE, "shop", "other")));
    }

    @Test
    public void collectionKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.collection("shop", "orders")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.collection("shop", "..")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.collection("cluster", "orders")));
    }

    @Test
    public void userKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.user("alice")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.user("al")));
    }

    @Test
    public void schemaKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.schema("shop", "orders")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.schema("shop", "a/b")));
    }

    @Test
    public void triggersKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.triggers("shop", "orders")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.triggers("admin_pages", "orders")));
    }

    @Test
    public void procedureKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.procedure("shop", "nightly")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.procedure("shop", "../../peer_escape_marker")));
    }

    @Test
    public void scheduleKeys() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.schedule("shop", "nightly")));
        assertFalse(AdminRecordNames.accepts(AdminRecordKey.schedule("shop", "a\\b")));
    }

    @Test
    public void rejectsAPipeInsideAName() {
        assertNull(AdminRecordNames.parse("collection|shop|orders|extra"));
        assertNull(AdminRecordNames.parse("procedure|shop|a|b"));
    }

    @Test
    public void rejectsAUserKeyWithADatabaseSegment() {
        assertNull(AdminRecordNames.parse("user|shop|alice"));
        assertFalse(AdminRecordNames.accepts(new AdminRecordKey(Kind.USER, "shop", "alice")));
    }

    @Test
    public void acceptsTheScriptRunsCollection() {
        assertTrue(AdminRecordNames.accepts(AdminRecordKey.collection("shop", "script_runs")));
    }

    @Test
    public void rejectsAKeyThatDoesNotRoundTrip() {
        assertNull(AdminRecordNames.parse("DATABASE|shop|shop"));
        assertNull(AdminRecordNames.parse("unknown|shop|shop"));
        assertNull(AdminRecordNames.parse("database|shop"));
        assertNull(AdminRecordNames.parse(null));
    }

    @Test
    public void parsesACanonicalKey() {
        assertEquals(AdminRecordKey.collection("shop", "orders"), AdminRecordNames.parse("collection|shop|orders"));
        assertEquals(AdminRecordKey.user("alice"), AdminRecordNames.parse("user||alice"));
    }
}
