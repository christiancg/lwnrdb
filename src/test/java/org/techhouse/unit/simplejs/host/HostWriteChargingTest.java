package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class HostWriteChargingTest {
    private static final long MEMORY_BUDGET = 8L * 1024 * 1024;

    private static final class IdOnlyDatabaseAccess extends FakeDatabaseAccess {
        @Override
        public JsonObject save(String db, String coll, JsonObject document) {
            super.save(db, coll, document);
            final var saved = new JsonObject();
            saved.add("_id", new JsonString("a"));
            return saved;
        }
    }

    private static ScriptResult run(String source, FakeDatabaseAccess database) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), database, null,
                new ResourceLimits(-1, 30_000L, 200, MEMORY_BUDGET)));
    }

    @Test
    public void test_saving_a_shared_string_dag_fails_on_the_memory_budget_before_the_write() {
        final var database = new IdOnlyDatabaseAccess();
        final var result = run("""
                import db from "db";
                const s = 'x'.repeat(1 << 20);
                const doc = { _id: 'a' };
                for (let i = 0; i < 50; i++) { doc['f' + i] = s; }
                db.save('d', 'c', doc);
                """, database);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName(), result.getErrorMessage());
        assertTrue(database.calls.isEmpty(), database.calls.toString());
    }

    @Test
    public void test_bulk_save_of_shared_strings_is_charged_per_occurrence() {
        final var database = new IdOnlyDatabaseAccess();
        final var result = run("""
                import db from "db";
                const s = 'x'.repeat(1 << 20);
                const docs = [];
                for (let i = 0; i < 50; i++) { docs.push({ _id: 'd' + i, body: s }); }
                db.bulkSave('d', 'c', docs);
                """, database);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName(), result.getErrorMessage());
        assertTrue(database.calls.isEmpty(), database.calls.toString());
    }

    @Test
    public void test_a_shared_key_is_charged_per_occurrence() {
        final var database = new IdOnlyDatabaseAccess();
        final var result = run("""
                import db from "db";
                const key = 'k'.repeat(1 << 18);
                const inner = { [key]: 1 };
                const doc = { _id: 'a', list: new Array(40).fill(inner) };
                db.save('d', 'c', doc);
                """, database);
        assertTrue(result.isError());
        assertEquals(ScriptErrorNames.MEMORY, result.getErrorName(), result.getErrorMessage());
    }

    @Test
    public void test_a_successful_write_releases_its_character_charge() {
        final var database = new IdOnlyDatabaseAccess();
        final var result = run("""
                import db from "db";
                const s = 'x'.repeat(100000);
                for (let i = 0; i < 100; i++) { db.save('d', 'c', { _id: 'a', body: s }); }
                return 'saved';
                """, database);
        assertFalse(result.isError(), result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals(100, database.calls.size());
    }
}
