package org.techhouse.unit.data.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.auth.ScriptPermissionLevel;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;

public class ScriptPermissionLevelTest {
    @Test
    public void test_covers_is_monotonic() {
        assertTrue(ScriptPermissionLevel.MANAGE.covers(ScriptPermissionLevel.MANAGE));
        assertTrue(ScriptPermissionLevel.MANAGE.covers(ScriptPermissionLevel.RUN));
        assertTrue(ScriptPermissionLevel.MANAGE.covers(ScriptPermissionLevel.NONE));
        assertTrue(ScriptPermissionLevel.RUN.covers(ScriptPermissionLevel.RUN));
        assertTrue(ScriptPermissionLevel.RUN.covers(ScriptPermissionLevel.NONE));
        assertFalse(ScriptPermissionLevel.RUN.covers(ScriptPermissionLevel.MANAGE));
        assertFalse(ScriptPermissionLevel.NONE.covers(ScriptPermissionLevel.RUN));
        assertFalse(ScriptPermissionLevel.NONE.covers(ScriptPermissionLevel.MANAGE));
    }

    @Test
    public void test_parse_or_none_accepts_every_level_name() {
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.parseOrNone("NONE"));
        assertEquals(ScriptPermissionLevel.RUN, ScriptPermissionLevel.parseOrNone("RUN"));
        assertEquals(ScriptPermissionLevel.MANAGE, ScriptPermissionLevel.parseOrNone("MANAGE"));
    }

    // A permission must never be widened by a parse guess
    @Test
    public void test_parse_or_none_defaults_to_none() {
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.parseOrNone("MANAGER"));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.parseOrNone("manage"));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.parseOrNone(""));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.parseOrNone(null));
    }

    @Test
    public void test_is_valid_name() {
        assertTrue(ScriptPermissionLevel.isValidName("RUN"));
        assertTrue(ScriptPermissionLevel.isValidName("MANAGE"));
        assertTrue(ScriptPermissionLevel.isValidName("NONE"));
        assertFalse(ScriptPermissionLevel.isValidName("READ"));
        assertFalse(ScriptPermissionLevel.isValidName(null));
    }

    @Test
    public void test_from_json_reads_the_legacy_boolean_form() {
        assertEquals(ScriptPermissionLevel.RUN, ScriptPermissionLevel.fromJson(new JsonBoolean(true)));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.fromJson(new JsonBoolean(false)));
    }

    @Test
    public void test_from_json_reads_the_level_form() {
        assertEquals(ScriptPermissionLevel.MANAGE, ScriptPermissionLevel.fromJson(new JsonString("MANAGE")));
        assertEquals(ScriptPermissionLevel.RUN, ScriptPermissionLevel.fromJson(new JsonString("RUN")));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.fromJson(new JsonString("nonsense")));
    }

    @Test
    public void test_from_json_denies_for_anything_else() {
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.fromJson(null));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.fromJson(JsonNull.INSTANCE));
        assertEquals(ScriptPermissionLevel.NONE, ScriptPermissionLevel.fromJson(new JsonNumber(1)));
    }
}
