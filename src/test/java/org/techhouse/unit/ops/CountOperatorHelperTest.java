package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.CountOperatorHelper;
import org.techhouse.ops.CountOperatorHelper.FastCount;
import org.techhouse.ops.PipelineContext;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.step.CountAggregationStep;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.TestGlobals;

public class CountOperatorHelperTest extends FilterResolutionSupport {
    private static final String FIELD = "tag";
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);

    private void seed() throws IOException, InterruptedException {
        seedValues();
        indexField(FIELD);
    }

    private void seedValues() throws IOException {
        insert("a", FIELD, new JsonString("Alpha"));
        insert("b", FIELD, new JsonString("alpha"));
        insert("c", FIELD, new JsonString("beta"));
        insert("d", FIELD, new JsonNumber(2));
        insert("e", FIELD, new JsonNumber(3));
        insert("f", FIELD, JsonNull.INSTANCE);
        insert("g", FIELD, JsonBoolean.TRUE);
        insert("h", FIELD, new JsonDateTime("#datetime(2024-01-01T10:00:00)"));
        insert("i");
    }

    private static FilterAggregationStep in(JsonBaseElement... operands) {
        return filter(FieldOperatorType.IN, FIELD, arrayOf(operands));
    }

    private static FastCount fastCountOf(FilterAggregationStep step) throws IOException {
        return CountOperatorHelper.tryIndexOnlyCount(List.of(step, new CountAggregationStep()), TestGlobals.DB,
                TestGlobals.COLL, PipelineContext.withoutScripts());
    }

    private static long countIn(FastCount fastCount) {
        return fastCount.result().get("count").asJsonNumber().getValue().longValue();
    }

    private void plantBetaIntoAlpha() throws IOException, InterruptedException {
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class).stream()
                    .filter(entry -> "Alpha".equals(entry.getValue())).findFirst().orElseThrow().getIds().add("c");
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_an_in_of_scalars_counts_from_the_index() throws Exception {
        seed();
        final List<FilterAggregationStep> queries = List.of(
                in(new JsonString("beta"), new JsonString("ALPHA"), new JsonString("zzz")),
                in(new JsonString("alpha"), new JsonString("Alpha"), new JsonString("alpha")),
                in(new JsonNumber(3), new JsonNumber(2.0), new JsonNumber(9), new JsonNumber(2)),
                in(JsonBoolean.TRUE, JsonBoolean.FALSE), in(new JsonDateTime("#datetime(2024-01-01T10:00:00)")),
                in(new JsonString("nothing")));
        for (final var query : queries) {
            final var fastCount = fastCountOf(query);

            assertNotNull(fastCount, query.toString());
            assertEquals(2, fastCount.nextStepIndex());
            assertEquals(rows(query).size(), countIn(fastCount), query.toString());
            assertEquals(rows(query).size(), countOf(query), query.toString());
        }

        plantBetaIntoAlpha();
        assertEquals(4L, countIn(fastCountOf(in(new JsonString("ALPHA"), new JsonString("beta")))),
                "an IN sums its runs' id sets, so an id planted in two runs counts twice");
    }

    @Test
    public void test_an_in_with_a_null_operand_falls_back() throws Exception {
        seed();
        final var query = in(new JsonString("alpha"), JsonNull.INSTANCE);

        assertEquals(3, rows(query).size());
        assertEquals(3, countOf(query));
        plantBetaIntoAlpha();
        assertEquals(3, countOf(query), "the fallback does not sum runs");
    }

    @Test
    public void test_an_in_mixing_types_falls_back() throws Exception {
        seed();
        final var query = in(new JsonString("alpha"), new JsonNumber(2));

        assertEquals(3, rows(query).size());
        assertEquals(3, countOf(query));
        plantBetaIntoAlpha();
        assertEquals(3, countOf(query), "a mixed list is never summed from one type's runs");
    }

    @Test
    public void test_an_in_of_objects_or_on_the_primary_key_is_not_summed() throws Exception {
        seedValues();
        insert("j", FIELD, new JsonObject());
        indexField(FIELD);

        assertNull(fastCountOf(in(new JsonObject())));
        assertEquals(rows(in(new JsonObject())).size(), countOf(in(new JsonObject())));
        final var byId = filter(FieldOperatorType.IN, "_id", arrayOf(new JsonString("a"), new JsonString("A")));
        assertEquals(1, countOf(byId));
        assertNull(fastCountOf(filter(FieldOperatorType.IN, FIELD, arrayOf())));
    }

    @Test
    public void test_pending_writes_force_the_reading_path() throws Exception {
        seed();
        plantBetaIntoAlpha();
        final var query = in(new JsonString("alpha"), new JsonString("beta"));
        assertEquals(4L, countIn(fastCountOf(query)));

        pending.markRecovered(TestGlobals.DB, TestGlobals.COLL, List.of("c"));
        try {
            assertEquals(3, countOf(query), "a pending id sends the count down the set path that corrects it");
        } finally {
            pending.clearCollection(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}
