package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.validations.AggregationStepValidator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexScanCustomAndMembershipTest {
    private static final String WHEN = "when";
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void insert(String id, String rawValue) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(IndexScanCustomAndMembershipTest.WHEN, new JsonString(rawValue));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, IndexScanCustomAndMembershipTest.WHEN);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL)
                .setIndexes(Set.of(IndexScanCustomAndMembershipTest.WHEN));
    }

    private int countRows(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).size();
    }

    @Test
    public void test_distinct_on_a_custom_field_buckets_the_same_with_and_without_an_index() throws Exception {
        insert("a", "#datetime(2024-01-01T10:00)");
        insert("b", "#datetime(2024-01-01T10:00:00)");
        final var viaScan = countRows(new DistinctAggregationStep(WHEN));

        enableIndex();
        final var viaIndex = countRows(new DistinctAggregationStep(WHEN));

        assertEquals(viaScan, viaIndex,
                "the build path bucketed custom values by their raw spelling while incremental maintenance"
                        + " matched them semantically, so the two disagreed with each other and with the scan");
    }

    @Test
    public void test_a_membership_operator_requires_an_array_value() {
        final var scalarOperand = new FieldOperator(FieldOperatorType.IN, "score", new JsonNumber(2));

        final var result = AggregationStepValidator.validateOperator(scalarOperand);

        assertFalse(result.isValid(),
                "a scalar IN reached SearchUtils and threw, so the same query answered 500 with an index and"
                        + " NO_RESULTS without one");
    }

    @Test
    public void test_not_in_requires_an_array_value_too() {
        final var scalarOperand = new FieldOperator(FieldOperatorType.NOT_IN, "score", new JsonString("x"));

        assertFalse(AggregationStepValidator.validateOperator(scalarOperand).isValid(), "NOT_IN shares the same throw");
    }

    @Test
    public void test_an_ordinary_equals_operator_is_still_accepted() {
        final var operator = new FieldOperator(FieldOperatorType.EQUALS, "score", new JsonNumber(2));

        org.junit.jupiter.api.Assertions.assertTrue(AggregationStepValidator.validateOperator(operator).isValid(),
                "only the membership operators take an array");
    }

    @Test
    public void test_a_filter_step_rejects_a_scalar_membership_operand() {
        final var step = new FilterAggregationStep(new FieldOperator(FieldOperatorType.IN, "score", new JsonNumber(2)));

        assertFalse(AggregationStepValidator.validate(step).isValid(),
                "the refusal must reach the request validator, not only the operator helper");
    }
}
