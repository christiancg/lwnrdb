package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class SortOperatorHelperTest {
    private static final String COLL = "rangetopk";
    private static final int LIMIT = 5;

    private final TopKFixture fixture = new TopKFixture(COLL);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        fixture.createIndexedOnTs(TopKFixture.standardDocuments());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        fixture.drop();
        TestUtils.standardTearDown();
    }

    private static FilterAggregationStep range(double above, double below) {
        return new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(TopKFixture.tsCompared(FieldOperatorType.GREATER_THAN, above),
                        TopKFixture.tsCompared(FieldOperatorType.SMALLER_THAN_EQUALS, below))));
    }

    private static BaseAggregationStep[] page(FilterAggregationStep filter, boolean ascending, int skip, int limit) {
        return new BaseAggregationStep[]{filter, new SortAggregationStep("ts", ascending),
                new SkipAggregationStep(skip), new LimitAggregationStep(limit)};
    }

    @Test
    public void test_a_range_top_k_skips_ids_before_fetching() {
        for (final var ascending : List.of(true, false)) {
            for (final var skip : List.of(1, 500, 1999)) {
                final var steps = page(range(100, 2500), ascending, skip, LIMIT);
                final var label = "ascending=" + ascending + " skip=" + skip;

                final var answer = fixture.run(steps);

                assertEquals(fixture.scanned(steps), answer.ids(), label);
                assertEquals(LIMIT, answer.ids().size(), label);
                assertEquals(LIMIT, answer.scanned(), label);
            }
        }
    }

    @Test
    public void test_a_skip_reaching_the_end_of_the_range_returns_the_tail() {
        final var tail = page(range(100, 2500), true, 2397, LIMIT);
        final var past = page(range(100, 2500), false, 5000, LIMIT);

        final var tailAnswer = fixture.run(tail);
        final var pastAnswer = fixture.run(past);

        assertEquals(fixture.scanned(tail), tailAnswer.ids());
        assertEquals(3, tailAnswer.ids().size());
        assertEquals(3, tailAnswer.scanned());
        assertEquals(List.of(), pastAnswer.ids());
        assertEquals(0, pastAnswer.scanned());
    }

    @Test
    public void test_a_zero_skip_and_a_zero_limit_keep_their_answers() {
        final var zeroSkip = page(range(10, 90), true, 0, LIMIT);
        final var zeroLimit = page(range(10, 90), true, 3, 0);

        assertEquals(fixture.scanned(zeroSkip), fixture.run(zeroSkip).ids());
        assertEquals(List.of(), fixture.run(zeroLimit).ids());
    }

    @Test
    public void test_pending_writes_keep_the_fetching_path() {
        fixture.save(TopKFixture.document("late", 1, 150));
        fixture.save(TopKFixture.document("d0007", 1, 101));
        assertFalse(IocContainer.get(PendingIndexWrites.class).idsFor(TestGlobals.DB, COLL).isEmpty());
        final var steps = page(range(100, 2500), true, 2, LIMIT);

        final var answer = fixture.run(steps);

        assertEquals(fixture.scanned(steps), answer.ids());
        assertTrue(answer.scanned() > LIMIT, "pending ids decline the range walk, read " + answer.scanned());
    }
}
