package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.ReduceAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationStreamLifetimeTest {
    private static final int REPETITIONS = 200;
    private static final int ALLOWED_DESCRIPTOR_DRIFT = 8;
    private static final String FIELD = "score";

    private OperationProcessor processor;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
        processor = new OperationProcessor();
        assertNull(IocContainer.get(Cache.class).getAdminPageEntries(TestGlobals.DB, TestGlobals.COLL),
                "the collection must have no page metadata, or the scan never takes the folder-scan fallback"
                        + " that holds an open directory handle");
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    @Test
    public void sort_over_an_unwritten_collection_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(new SortAggregationStep(FIELD, true)));
    }

    @Test
    public void bounded_sort_over_an_unwritten_collection_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(new SortAggregationStep(FIELD, true), new LimitAggregationStep(2)));
    }

    @Test
    public void group_by_over_an_unwritten_collection_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(new GroupByAggregationStep(FIELD)));
    }

    @Test
    public void join_over_an_unwritten_collection_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(new JoinAggregationStep(TestGlobals.JOIN_COLL, FIELD, FIELD, "joined")));
    }

    @Test
    public void count_after_an_unindexed_filter_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(unindexedFilter(), new CountAggregationStep()));
    }

    @Test
    public void reduce_over_an_unwritten_collection_does_not_leak_descriptors() {
        assertNoDescriptorLeak(List.of(new ReduceAggregationStep("export default (acc, doc) => acc + doc.score;",
                new JsonNumber(0), "total")));
    }

    @Test
    public void filter_and_distinct_stay_leak_free() {
        assertNoDescriptorLeak(List.of(unindexedFilter(), new DistinctAggregationStep(FIELD)));
    }

    private static FilterAggregationStep unindexedFilter() {
        return new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.NOT_EQUALS, FIELD, new JsonString("absent")));
    }

    private void assertNoDescriptorLeak(List<BaseAggregationStep> steps) {
        final var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        assumeTrue(operatingSystem instanceof UnixOperatingSystemMXBean,
                "open descriptor counts are only readable through the Unix operating system MXBean");
        final var unix = (UnixOperatingSystemMXBean) operatingSystem;
        aggregate(steps);
        final var before = unix.getOpenFileDescriptorCount();
        for (var i = 0; i < REPETITIONS; i++) {
            aggregate(steps);
        }
        final var leaked = unix.getOpenFileDescriptorCount() - before;
        assertTrue(leaked <= ALLOWED_DESCRIPTOR_DRIFT, REPETITIONS + " aggregations leaked " + leaked
                + " file descriptors; a blocking step is not closing the stream it consumed");
    }

    private void aggregate(List<BaseAggregationStep> steps) {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        final var response = processor.processMessage(request);
        assertTrue(response.getStatus() == OperationStatus.OK || response.getStatus() == OperationStatus.NOT_FOUND,
                "the pipeline must really run, or the descriptor count proves nothing: " + response.getStatus() + " "
                        + response.getErrorCode());
    }
}
