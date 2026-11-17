package org.techhouse.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.unit.ops.FilterResolutionSupport;

public class SelectiveLegsTest extends FilterResolutionSupport {
    private static final int DOCUMENTS = 100;

    private void seed() throws Exception {
        for (var i = 0; i < DOCUMENTS; i++) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("p" + i));
            object.add("price", new JsonNumber(i * 10));
            object.add("label", new JsonString(i % 2 == 0 ? "even" : "odd"));
            object.add("uid", new JsonString("u" + i));
            object.add("tier", new JsonString("t" + i % 3));
            final var entry = org.techhouse.data.DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
            entry.set_id("p" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        for (final var field : List.of("price", "label", "uid", "tier")) {
            indexField(field);
        }
    }

    private static FieldOperator equalsTo(String field, String value) {
        return new FieldOperator(FieldOperatorType.EQUALS, field, new JsonString(value));
    }

    private static FieldOperator price(FieldOperatorType type, double value) {
        return new FieldOperator(type, "price", new JsonNumber(value));
    }

    private static ConjunctionOperator and(BaseOperator... legs) {
        return new ConjunctionOperator(ConjunctionOperatorType.AND, List.of(legs));
    }

    private Set<String> candidates(ConjunctionOperator operator) throws IOException {
        return ConjunctionCandidates.of(operator, TestGlobals.DB, TestGlobals.COLL, PipelineContext.withoutScripts());
    }

    private Set<String> filtered(BaseOperator operator, AnalyzeContext analyze) throws IOException {
        return FilterOperatorHelper
                .processOperator(operator, null, TestGlobals.DB, TestGlobals.COLL,
                        PipelineContext.of(0L, Long.MAX_VALUE, analyze))
                .map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    public void andPicksMostSelectiveLeg() throws Exception {
        seed();
        final var operator = and(equalsTo("label", "odd"), equalsTo("uid", "u4"));
        final var analyze = new AnalyzeContext();

        assertEquals(Set.of("p4"), candidates(operator), "the broad leg is tested on the document, not intersected");
        assertEquals(Set.of(), filtered(operator, analyze));
        assertEquals(1, analyze.getDocumentsScanned());
        assertTrue(analyze.getIndexesUsed().containsAll(List.of("label", "uid")),
                "a leg chosen by its count still reports its index");
    }

    @Test
    public void aNarrowRangeLegIsResolvedAloneAndTheEqualityRetested() throws Exception {
        seed();
        final var operator = and(equalsTo("label", "even"), price(FieldOperatorType.GREATER_THAN_EQUALS, 980));

        assertEquals(Set.of("p98", "p99"), candidates(operator));
        assertEquals(Set.of("p98"), filtered(operator, new AnalyzeContext()));
    }

    @Test
    public void legsOfComparableSizeAreStillIntersected() throws Exception {
        seed();
        final var operator = and(equalsTo("label", "odd"), equalsTo("tier", "t0"));

        assertEquals(filtered(operator, new AnalyzeContext()), candidates(operator));
    }

    @Test
    public void everyShapeAnswersLikeTheScan() throws Exception {
        seed();
        final var shapes = List.of(and(equalsTo("label", "odd"), equalsTo("uid", "u5")),
                and(equalsTo("label", "odd"), equalsTo("uid", "u5"), price(FieldOperatorType.SMALLER_THAN, 100)),
                and(equalsTo("tier", "t2"), price(FieldOperatorType.GREATER_THAN, 500), equalsTo("label", "even")),
                and(equalsTo("label", "odd"), equalsTo("uid", "missing")),
                and(equalsTo("label", "none"), equalsTo("tier", "t1")));
        for (final var shape : shapes) {
            final var scan = new TreeSet<>(ids(new SkipAggregationStep(0), new FilterAggregationStep(shape)));

            assertEquals(scan, filtered(shape, new AnalyzeContext()), shape.toString());
            assertTrue(candidates(shape).containsAll(scan), shape.toString());
        }
    }
}
