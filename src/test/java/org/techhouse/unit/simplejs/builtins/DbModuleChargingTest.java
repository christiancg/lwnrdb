package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.BulkSaveOutcome;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

// What the script pulls out of the database is charged against the same allocation budget as what it
// allocates itself, so a runaway read aborts instead of materialising.
public class DbModuleChargingTest {
    private static final String PADDING = "x".repeat(512);

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("payload", new JsonString(PADDING));
        return object;
    }

    private static final class CountingList extends AbstractList<JsonObject> {
        private final List<JsonObject> backing;
        private int read;

        private CountingList(List<JsonObject> backing) {
            this.backing = backing;
        }

        @Override
        public JsonObject get(int index) {
            read++;
            return backing.get(index);
        }

        @Override
        public int size() {
            return backing.size();
        }
    }

    private static final class BigResultDatabase extends FakeDatabaseAccess {
        private final CountingList results;

        private BigResultDatabase(int documents) {
            final var backing = new ArrayList<JsonObject>();
            for (var i = 0; i < documents; i++) {
                backing.add(document("d" + i));
            }
            this.results = new CountingList(backing);
        }

        @Override
        public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
            calls.add("aggregate:" + db + "/" + coll + "/" + pipeline.size());
            return results;
        }

        @Override
        public JsonObject findById(String db, String coll, String id) {
            calls.add("findById:" + db + "/" + coll + "/" + id);
            return document(id);
        }

        @Override
        public JsonObject save(String db, String coll, JsonObject document) {
            calls.add("save:" + db + "/" + coll);
            return document;
        }

        @Override
        public BulkSaveOutcome bulkSave(String db, String coll, List<JsonObject> documents) {
            calls.add("bulkSave:" + db + "/" + coll + "/" + documents.size());
            final var inserted = new ArrayList<String>();
            for (var i = 0; i < 5000; i++) {
                inserted.add("i" + i);
            }
            return new BulkSaveOutcome(inserted, List.of("u1"));
        }
    }

    private static ResourceLimits budget(long memory) {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, memory, -1, -1, -1);
    }

    private static ScriptResult run(FakeDatabaseAccess database, String source, long memory) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), database, null, budget(memory)));
    }

    @Test
    public void test_aggregate_charges_each_document() {
        final var database = new BigResultDatabase(500);
        final var result = run(database, "import db from \"db\"; return db.aggregate('d', 'c', []).length;", 8192);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // The abort has to happen inside the conversion loop, not after the whole JS copy already exists
    @Test
    public void test_aggregate_aborts_partway_through_conversion() {
        final var database = new BigResultDatabase(500);
        run(database, "import db from \"db\"; db.aggregate('d', 'c', []);", 8192);
        assertTrue(database.results.read > 0, "some documents must have been converted");
        assertTrue(database.results.read < 500,
                "the whole list must not have been converted: " + database.results.read);
    }

    @Test
    public void test_find_by_id_charges_the_document() {
        final var database = new BigResultDatabase(1);
        final var result = run(database, "import db from \"db\"; return db.findById('d', 'c', 'x1');", 128);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    @Test
    public void test_bulk_save_charges_the_outcome_arrays() {
        final var database = new BigResultDatabase(1);
        final var result = run(database, "import db from \"db\"; return db.bulkSave('d', 'c', [{}]);", 16384);
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // The argument is charged when the script builds it, never again on the way out
    @Test
    public void test_save_argument_is_not_charged_twice() {
        final var database = new BigResultDatabase(1);
        final var result = run(database, """
                import db from "db";
                const document = { _id: "s1", payload: "y".repeat(1000) };
                return db.save('d', 'c', document)._id;
                """, 6000);
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("s1", result.getValue().asJsonString().getValue());
    }
}
