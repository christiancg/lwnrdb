package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class RangeTopKWalkTest {
    private static final String COLL = "rangetopk";
    private static final int CROWD = 4700;
    private static final int MATCHING = 300;
    private static final int LIMIT = 5;

    private final TopKFixture fixture = new TopKFixture(COLL);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < CROWD; i++) {
            documents.add(TopKFixture.document(String.format("a%04d", i), 0, 1));
        }
        for (var i = 0; i < MATCHING; i++) {
            documents.add(TopKFixture.document(String.format("z%03d", i), 1, 1));
        }
        fixture.createIndexedOnTs(documents);
        final var created = IocContainer.get(OperationProcessor.class)
                .processMessage(new CreateIndexRequest(TestGlobals.DB, COLL, "region"));
        assertEquals(OperationStatus.OK, created.getStatus(), created.getMessage());
        fixture.drain();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        fixture.drop();
        TestUtils.standardTearDown();
    }

    private static BaseAggregationStep[] rangeTopK() {
        final var filter = new FilterAggregationStep(new ConjunctionOperator(ConjunctionOperatorType.AND,
                List.of(TopKFixture.tsCompared(FieldOperatorType.GREATER_THAN_EQUALS, 0),
                        new FieldOperator(FieldOperatorType.EQUALS, "region", new JsonString("r1")))));
        return new BaseAggregationStep[]{filter, new SortAggregationStep("ts", true), new LimitAggregationStep(LIMIT)};
    }

    @Test
    public void aLargeEntryIsSortedOncePerWalk() {
        final var steps = rangeTopK();
        final var expected = fixture.scanned(steps);
        final TopKFixture.Answer answer;
        try (var reader = mockStatic(IndexEntryReader.class, CALLS_REAL_METHODS)) {
            answer = fixture.run(steps);
            reader.verify(() -> IndexEntryReader.sortedIds(any()), times(1));
        }
        assertEquals(expected, answer.ids());
        assertEquals(List.of("z000", "z001", "z002", "z003", "z004"), answer.ids());
        assertTrue(answer.scanned() < CROWD, "read " + answer.scanned());
    }
}
