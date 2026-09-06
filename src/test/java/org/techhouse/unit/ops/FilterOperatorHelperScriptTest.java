package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.PipelineScriptContext;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.operators.ScriptOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterOperatorHelperScriptTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
        AnalyzeContext.clear();
    }

    private static JsonObject document(String id, double price) {
        final var document = new JsonObject();
        document.add("_id", new JsonString(id));
        document.add("price", new JsonNumber(price));
        return document;
    }

    private static Stream<JsonObject> documents() {
        return Stream.of(document("1", 5), document("2", 15), document("3", 25));
    }

    @Test
    public void test_filters_by_predicate() throws IOException {
        try (var context = new PipelineScriptContext()) {
            final var operator = new ScriptOperator("export default (doc) => doc.price > 10;");
            final var results = FilterOperatorHelper
                    .processOperator(operator, documents(), TestGlobals.DB, TestGlobals.COLL, context).toList();
            assertEquals(2, results.size());
            assertEquals("2", results.getFirst().get("_id").asJsonString().getValue());
        }
    }

    @Test
    public void test_truthy_non_boolean_is_accepted() throws IOException {
        try (var context = new PipelineScriptContext()) {
            final var operator = new ScriptOperator("export default (doc) => doc.price > 10 ? 'yes' : 0;");
            assertEquals(2, FilterOperatorHelper
                    .processOperator(operator, documents(), TestGlobals.DB, TestGlobals.COLL, context).count());
        }
    }

    @Test
    public void test_falsy_values_exclude() throws IOException {
        final var falsy = List.of("0", "''", "null", "undefined", "NaN", "false");
        for (final var value : falsy) {
            try (var context = new PipelineScriptContext()) {
                final var operator = new ScriptOperator("export default (doc) => " + value + ";");
                assertEquals(0, FilterOperatorHelper
                        .processOperator(operator, documents(), TestGlobals.DB, TestGlobals.COLL, context).count(),
                        value + " must be falsy");
            }
        }
    }

    @Test
    public void test_truthy_values_include() throws IOException {
        final var truthy = List.of("1", "'x'", "true", "({})", "[]");
        for (final var value : truthy) {
            try (var context = new PipelineScriptContext()) {
                final var operator = new ScriptOperator("export default (doc) => " + value + ";");
                assertEquals(3, FilterOperatorHelper
                        .processOperator(operator, documents(), TestGlobals.DB, TestGlobals.COLL, context).count(),
                        value + " must be truthy");
            }
        }
    }

    @Test
    public void test_script_operator_is_not_index_resolvable() throws IOException {
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(new ScriptOperator("export default (doc) => true;"),
                TestGlobals.DB, TestGlobals.COLL));
    }

    // A conjunction containing a script still resolves its other operands via index, so the whole
    // conjunction is disqualified from the index-only COUNT but not from index use in the FILTER itself.
    @Test
    public void test_conjunction_with_a_script_is_not_index_resolvable() throws IOException {
        final List<BaseOperator> operators = new ArrayList<>();
        operators.add(new FieldOperator(FieldOperatorType.EQUALS, "price", new JsonNumber(5)));
        operators.add(new ScriptOperator("export default (doc) => true;"));
        final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
        assertNull(FilterOperatorHelper.resolveIdsViaIndex(conjunction, TestGlobals.DB, TestGlobals.COLL));
    }

    // The conjunction runs against the collection (each branch reads it independently), which is how a
    // FILTER is reached as the pipeline's first step.
    @Test
    public void test_conjunction_combines_a_field_operator_and_a_script() throws IOException {
        seedCollection();
        try (var context = new PipelineScriptContext()) {
            final List<BaseOperator> operators = new ArrayList<>();
            operators.add(new FieldOperator(FieldOperatorType.GREATER_THAN, "price", new JsonNumber(10)));
            operators.add(new ScriptOperator("export default (doc) => doc.price < 20;"));
            final var conjunction = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);
            final var results = FilterOperatorHelper
                    .processOperator(conjunction, null, TestGlobals.DB, TestGlobals.COLL, context).toList();
            assertEquals(1, results.size());
            assertEquals("2", results.getFirst().get("_id").asJsonString().getValue());
        }
    }

    @Test
    public void test_script_scans_the_collection_when_there_is_no_upstream_stream() throws IOException {
        seedCollection();
        try (var context = new PipelineScriptContext()) {
            final var operator = new ScriptOperator("export default (doc) => doc.price > 10;");
            assertEquals(2, FilterOperatorHelper
                    .processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL, context).count());
        }
    }

    private void seedCollection() {
        final var cache = IocContainer.get(Cache.class);
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        var position = 0;
        for (final var document : documents().toList()) {
            final var id = document.get("_id").asJsonString().getValue();
            final var entry = new DbEntry();
            entry.setDatabaseName(TestGlobals.DB);
            entry.setCollectionName(TestGlobals.COLL);
            entry.setData(document);
            entry.set_id(id);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
            cache.putAdminCollectionEntry(adminCollEntry,
                    new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, id, position, 100, 0));
            position += 100;
        }
    }

    @Test
    public void test_analyze_records_script_invocations() throws IOException {
        final var analyze = new AnalyzeContext();
        AnalyzeContext.set(analyze);
        try (var context = new PipelineScriptContext()) {
            final var operator = new ScriptOperator("export default (doc) => true;");
            assertEquals(3, FilterOperatorHelper
                    .processOperator(operator, documents(), TestGlobals.DB, TestGlobals.COLL, context).count());
            assertEquals(3, analyze.getScriptInvocations());
        }
    }

    @Test
    public void test_empty_stream_never_calls_the_script() throws IOException {
        try (var context = new PipelineScriptContext()) {
            final var operator = new ScriptOperator("export default (doc) => { throw new Error('called'); };");
            assertEquals(0, FilterOperatorHelper
                    .processOperator(operator, Stream.of(), TestGlobals.DB, TestGlobals.COLL, context).count());
        }
    }
}
