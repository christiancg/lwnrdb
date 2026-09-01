package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.unit.simplejs.host.FakeDatabaseAccess;

// db.cursor pages a pipeline through the ordinary AGGREGATE path, so only one batch is ever in heap.
public class DbCursorTest {
    private static final String SORT_STEP = "[{ type: 'SORT', fieldName: '_id', ascending: true }]";

    private static final class PagingDatabase extends FakeDatabaseAccess {
        final List<JsonArray> pipelines = new ArrayList<>();
        private final List<JsonObject> documents = new ArrayList<>();
        private int failFromBatch = Integer.MAX_VALUE;
        private String padding = "";

        private PagingDatabase(int total) {
            for (var i = 0; i < total; i++) {
                final var document = new JsonObject();
                document.add("_id", new JsonString("d" + i));
                documents.add(document);
            }
        }

        @Override
        public List<JsonObject> aggregate(String db, String coll, JsonArray pipeline) {
            calls.add("aggregate:" + db + "/" + coll + "/" + pipeline.size());
            pipelines.add(pipeline);
            if (pipelines.size() >= failFromBatch) {
                final var error = new JsObject();
                error.set("name", new JsString("Error"));
                error.set("message", new JsString("collection was dropped"));
                throw new JsThrowException(error);
            }
            final var skip = value(pipeline, pipeline.size() - 2, "skip");
            final var limit = value(pipeline, pipeline.size() - 1, "limit");
            final var page = new ArrayList<JsonObject>();
            for (var i = skip; i < Math.min(skip + limit, documents.size()); i++) {
                final var document = documents.get(i);
                if (!padding.isEmpty()) {
                    document.add("payload", new JsonString(padding));
                }
                page.add(document);
            }
            return page;
        }

        private static int value(JsonArray pipeline, int index, String field) {
            return pipeline.get(index).asJsonObject().get(field).asJsonNumber().asInteger();
        }
    }

    private static ResourceLimits limits(long memory, int batchSize, int maxBatchSize) {
        return new ResourceLimits(-1, -1, -1, true, false, List.of(), -1, -1, false, false,
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, memory, -1, batchSize, maxBatchSize);
    }

    private static ScriptResult run(PagingDatabase database, String source, ResourceLimits limits) {
        return new SimpleJs().run(source, new SimpleHostBindings(new JsonObject(), database, null, limits));
    }

    private static ScriptResult run(PagingDatabase database, String source) {
        return run(database, source, limits(-1, 500, 5000));
    }

    private static String walk(String options) {
        return """
                import db from "db";
                const seen = [];
                for (const doc of db.cursor('d', 'c', %s, %s)) {
                    seen.push(doc._id);
                }
                return seen;
                """.formatted(SORT_STEP, options);
    }

    private static List<String> ids(ScriptResult result) {
        assertFalse(result.isError(), result.getErrorMessage());
        final var ids = new ArrayList<String>();
        for (final var element : result.getValue().asJsonArray()) {
            ids.add(element.asJsonString().getValue());
        }
        return ids;
    }

    @Test
    public void test_iterates_every_document_across_batches() {
        final var database = new PagingDatabase(5);
        final var seen = ids(run(database, walk("{ batchSize: 2 }")));
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), seen);
        assertEquals(3, database.pipelines.size());
    }

    // A short batch ends the walk: there is no extra empty round trip
    @Test
    public void test_stops_on_a_short_batch() {
        final var database = new PagingDatabase(3);
        assertEquals(3, ids(run(database, walk("{ batchSize: 2 }"))).size());
        assertEquals(2, database.pipelines.size());
    }

    @Test
    public void test_appends_skip_and_limit_steps() {
        final var database = new PagingDatabase(5);
        ids(run(database, walk("{ batchSize: 2 }")));
        for (var batch = 0; batch < database.pipelines.size(); batch++) {
            final var pipeline = database.pipelines.get(batch);
            assertEquals(3, pipeline.size());
            assertEquals("SORT", pipeline.get(0).asJsonObject().get("type").asJsonString().getValue());
            final var skip = pipeline.get(1).asJsonObject();
            assertEquals("SKIP", skip.get("type").asJsonString().getValue());
            assertEquals(batch * 2, skip.get("skip").asJsonNumber().asInteger());
            final var limit = pipeline.get(2).asJsonObject();
            assertEquals("LIMIT", limit.get("type").asJsonString().getValue());
            assertEquals(2, limit.get("limit").asJsonNumber().asInteger());
        }
    }

    @Test
    public void test_does_not_mutate_the_caller_pipeline() {
        final var database = new PagingDatabase(3);
        final var result = run(database, """
                import db from "db";
                const steps = %s;
                for (const doc of db.cursor('d', 'c', steps, { batchSize: 2 })) {
                }
                return steps.length;
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(1, result.getValue().asJsonNumber().asInteger());
    }

    @Test
    public void test_clamps_batch_size_to_the_configured_maximum() {
        final var database = new PagingDatabase(5);
        ids(run(database, walk("{ batchSize: 1000 }"), limits(-1, 2, 2)));
        assertEquals(2, database.pipelines.getFirst().get(2).asJsonObject().get("limit").asJsonNumber().asInteger());
    }

    @Test
    public void test_uses_the_configured_default_batch_size() {
        final var database = new PagingDatabase(1);
        ids(run(database, walk("undefined"), limits(-1, 7, 5000)));
        assertEquals(7, database.pipelines.getFirst().get(2).asJsonObject().get("limit").asJsonNumber().asInteger());
    }

    @Test
    public void test_rejects_non_positive_batch_size() {
        for (final var batchSize : List.of("0", "-1", "NaN", "'nope'")) {
            final var database = new PagingDatabase(3);
            final var result = run(database, walk("{ batchSize: " + batchSize + " }"));
            assertTrue(result.isError(), "batchSize " + batchSize + " must be rejected");
            assertEquals("RangeError", result.getErrorName());
            assertTrue(database.pipelines.isEmpty(), "nothing must be read for " + batchSize);
        }
    }

    // A numeric string still coerces, which is what every other db argument does
    @Test
    public void test_accepts_a_numeric_string_batch_size() {
        final var database = new PagingDatabase(3);
        assertEquals(3, ids(run(database, walk("{ batchSize: '2' }"))).size());
    }

    @Test
    public void test_empty_first_batch_yields_no_iterations() {
        final var database = new PagingDatabase(0);
        assertEquals(List.of(), ids(run(database, walk("{ batchSize: 2 }"))));
        assertEquals(1, database.pipelines.size());
    }

    // Spread and the iterator helpers prove the realm prototype is linked behind %IteratorPrototype%
    @Test
    public void test_supports_spread_and_iterator_helpers() {
        final var database = new PagingDatabase(5);
        final var spread = run(database, """
                import db from "db";
                return [...db.cursor('d', 'c', %s, { batchSize: 2 })].map(d => d._id);
                """.formatted(SORT_STEP));
        assertEquals(List.of("d0", "d1", "d2", "d3", "d4"), ids(spread));

        final var helper = new PagingDatabase(5);
        final var taken = run(helper, """
                import db from "db";
                return db.cursor('d', 'c', %s, { batchSize: 2 }).take(2).toArray().map(d => d._id);
                """.formatted(SORT_STEP));
        assertEquals(List.of("d0", "d1"), ids(taken));
        assertEquals(1, helper.pipelines.size(), "take(2) must not fetch a second batch");
    }

    @Test
    public void test_charges_each_document_as_it_is_converted() {
        final var database = new PagingDatabase(200);
        database.padding = "x".repeat(512);
        final var result = run(database, walk("{ batchSize: 50 }"), limits(8192, 500, 5000));
        assertTrue(result.isError());
        assertEquals("ScriptMemoryError", result.getErrorName());
    }

    // A drained batch is credited back, so a walk costs one batch of budget rather than the whole
    // collection - which is what makes the cursor the memory-safe spelling of a big read
    @Test
    public void test_a_drained_batch_is_refunded() {
        final var database = new PagingDatabase(200);
        database.padding = "x".repeat(512);
        assertEquals(200, ids(run(database, walk("{ batchSize: 5 }"), limits(65_536, 500, 5000))).size());
    }

    // The db failure contract still holds mid-iteration: a dropped collection is a catchable Error
    @Test
    public void test_error_from_aggregate_surfaces_as_a_catchable_error() {
        final var database = new PagingDatabase(5);
        database.failFromBatch = 2;
        final var result = run(database, """
                import db from "db";
                const seen = [];
                try {
                    for (const doc of db.cursor('d', 'c', %s, { batchSize: 2 })) {
                        seen.push(doc._id);
                    }
                } catch (e) {
                    return e.message + " after " + seen.length;
                }
                return "no error";
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("collection was dropped after 2", result.getValue().asJsonString().getValue());
    }

    // Abandoning a cursor holds nothing server-side: the run ends with no release call of any kind
    @Test
    public void test_abandoned_cursor_leaves_no_state_behind() {
        final var database = new PagingDatabase(5);
        final var result = run(database, """
                import db from "db";
                for (const doc of db.cursor('d', 'c', %s, { batchSize: 2 })) {
                    break;
                }
                return "done";
                """.formatted(SORT_STEP));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(List.of("aggregate:d/c/3"), database.calls);
    }

    @Test
    public void test_requires_an_array_of_steps() {
        final var database = new PagingDatabase(1);
        final var result = run(database, "import db from \"db\"; db.cursor('d', 'c', 'nope');");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }
}
