package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

public class SimpleJsInternalErrorTest {
    private static final String INTERNAL_ERROR = "InternalError";

    private static final class UnmappedFailureDatabaseAccess extends FakeDatabaseAccess {
        @Override
        public JsonObject findById(String db, String coll, String id) {
            throw new IndexOutOfBoundsException("Index 2 out of bounds for length 2");
        }
    }

    private static ScriptResult run(String source) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), new UnmappedFailureDatabaseAccess(),
                null, ResourceLimits.unlimited()));
    }

    @Test
    public void test_an_unmapped_runtime_exception_becomes_a_script_error() {
        final var result = run("""
                import db from "db";
                db.findById("d", "c", "x");
                """);
        assertTrue(result.isError());
        assertEquals(INTERNAL_ERROR, result.getErrorName());
        assertTrue(result.getErrorMessage().contains("IndexOutOfBoundsException"), result.getErrorMessage());
    }

    @Test
    public void test_an_unmapped_runtime_exception_does_not_escape_a_script_try_block() {
        final var result = run("""
                import db from "db";
                try { db.findById("d", "c", "x"); } catch (e) { return "caught"; }
                return "not thrown";
                """);
        assertTrue(result.isError());
        assertEquals(INTERNAL_ERROR, result.getErrorName());
    }

    @Test
    public void test_a_thrown_script_error_keeps_its_own_name() {
        final var result = new SimpleJs().run("throw new TypeError('nope');", SimpleHostBindings.empty());
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_a_type_error_from_a_host_builtin_stays_catchable() {
        final var result = run("""
                import db from "db";
                try { db.save("d", "c"); } catch (e) { return "caught " + e.name; }
                return "not thrown";
                """);
        assertFalse(result.isError());
        assertEquals("caught TypeError", result.getValue().asJsonString().getValue());
    }
}
