package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * A null operand is the one kind that is not a JsonPrimitive, so it reaches getTester's fallthrough
 * rather than any of the typed branches. Agreement testing cannot catch a wrong answer here - the
 * index has no JsonNull case and always declines, so both paths scan and agree with each other
 * however wrong they are. These cases pin the answers themselves.
 */
public class FilterNullOperandTest {
    private static final String FIELD = "status";
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        insert("n_null", JsonNull.INSTANCE);
        insert("n_string", new JsonString("open"));
        insert("n_number", new JsonNumber(7));
        insert("n_absent", null);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void insert(String id, JsonBaseElement value) throws IOException {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        if (value != null) {
            object.add(FIELD, value);
        }
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void indexField() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new java.util.HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    private List<String> matchedIds(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        final var ids = new ArrayList<String>();
        for (final var document : AggregationOperationHelper.processAggregation(request)) {
            ids.add(document.get(Globals.PK_FIELD).asJsonString().getValue());
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    private List<String> againstNull(FieldOperatorType type) throws IOException {
        return matchedIds(new FilterAggregationStep(new FieldOperator(type, FIELD, JsonNull.INSTANCE)));
    }

    @Test
    public void test_equals_null_returns_only_the_null_valued_document() throws IOException {
        assertEquals(List.of("n_null"), againstNull(FieldOperatorType.EQUALS));
    }

    @Test
    public void test_not_equals_null_returns_the_non_null_documents() throws IOException {
        assertEquals(List.of("n_number", "n_string"), againstNull(FieldOperatorType.NOT_EQUALS));
    }

    @Test
    public void test_range_operators_against_null_match_nothing() throws IOException {
        for (final var type : List.of(FieldOperatorType.GREATER_THAN, FieldOperatorType.GREATER_THAN_EQUALS,
                FieldOperatorType.SMALLER_THAN, FieldOperatorType.SMALLER_THAN_EQUALS)) {
            assertEquals(List.of(), againstNull(type), type + " has no meaning against null");
        }
    }

    @Test
    public void test_contains_null_matches_nothing() throws IOException {
        assertEquals(List.of(), againstNull(FieldOperatorType.CONTAINS));
    }

    @Test
    public void test_a_document_missing_the_field_matches_neither_equals_nor_not_equals_null() throws IOException {
        assertFalse(againstNull(FieldOperatorType.EQUALS).contains("n_absent"), "an absent field is not a null value");
        assertFalse(againstNull(FieldOperatorType.NOT_EQUALS).contains("n_absent"),
                "an absent field is not a value that differs from null");
    }

    @Test
    public void test_nor_with_a_null_operand_agrees_with_the_inverted_filter() throws IOException {
        final var nor = matchedIds(new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.NOR,
                List.of(new FieldOperator(FieldOperatorType.EQUALS, FIELD, JsonNull.INSTANCE)))));

        assertEquals(List.of("n_absent", "n_number", "n_string"), nor);
    }

    @Test
    public void test_every_answer_is_unchanged_once_the_field_is_indexed() throws Exception {
        final var before = new ArrayList<List<String>>();
        for (final var type : FieldOperatorType.values()) {
            if (type == FieldOperatorType.IN || type == FieldOperatorType.NOT_IN) {
                continue;
            }
            before.add(againstNull(type));
        }

        indexField();

        final var after = new ArrayList<List<String>>();
        for (final var type : FieldOperatorType.values()) {
            if (type == FieldOperatorType.IN || type == FieldOperatorType.NOT_IN) {
                continue;
            }
            after.add(againstNull(type));
        }
        assertEquals(before, after, "a null operand is always scan-answered, so indexing must change nothing");
    }
}
