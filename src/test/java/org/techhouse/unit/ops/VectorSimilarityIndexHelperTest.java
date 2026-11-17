package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.VectorSimilarityIndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.test.IndexRaceSupport;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class VectorSimilarityIndexHelperTest {
    @Test
    public void test_non_nearest_operator_is_not_index_accelerable() throws Exception {
        final var op = new CustomOperator("mystery", "embedding", null, new JsonObject());

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll", ReadObserver.NONE));
    }

    @Test
    public void test_exact_query_is_not_index_accelerable() throws Exception {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(5));
        args.add("exact", JsonBoolean.of(true));
        final var op = new CustomOperator("nearest", "embedding", query, args);

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll", ReadObserver.NONE));
    }

    private static void seedVectorIndex() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var cache = IocContainer.get(Cache.class);
        for (var i = 0; i < 40; i++) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("v" + i));
            object.add("embedding", new JsonVector("#vector(" + (i / 40.0) + ",1.0)"));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
            entry.set_id("v" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "embedding");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("embedding"));
    }

    private static CustomOperator nearest() {
        final var query = new JsonVector("#vector(0.5,1.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(5));
        args.add("exact", JsonBoolean.of(false));
        return new CustomOperator("nearest", "embedding", query, args);
    }

    @Test
    public void test_neighbourhood_collect_holds_the_field_lock() throws Exception {
        seedVectorIndex();
        try {
            final var operator = nearest();
            IndexRaceSupport.readWhileTheIndexerChurns(TestGlobals.DB, TestGlobals.COLL, "embedding", JsonVector.class,
                    () -> VectorSimilarityIndexHelper.candidateIds(operator, TestGlobals.DB, TestGlobals.COLL,
                            ReadObserver.NONE));
        } finally {
            TestUtils.standardTearDown();
        }
    }

    private static final int LARGE_COLLECTION_SIZE = 150;

    private static void seedLargeVectorIndex() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var cache = IocContainer.get(Cache.class);
        for (var i = 0; i < LARGE_COLLECTION_SIZE; i++) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("v" + i));
            object.add("embedding", new JsonVector("#vector(" + (i / (double) LARGE_COLLECTION_SIZE) + ",1.0)"));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
            entry.set_id("v" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "embedding");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("embedding"));
    }

    private static CustomOperator nearestAtTheValidatorCeiling() {
        final var query = new JsonVector("#vector(0.5,1.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(214_748_364.0));
        args.add("exact", JsonBoolean.of(false));
        return new CustomOperator("nearest", "embedding", query, args);
    }

    @Test
    public void test_candidate_ids_budget_stays_well_formed_near_the_validator_ceiling() throws Exception {
        seedLargeVectorIndex();
        try {
            final var operator = nearestAtTheValidatorCeiling();

            final var candidates = VectorSimilarityIndexHelper.candidateIds(operator, TestGlobals.DB, TestGlobals.COLL,
                    ReadObserver.NONE);

            assertNotNull(candidates);
            assertEquals(LARGE_COLLECTION_SIZE, candidates.size(),
                    "a k this large must not collapse the candidate budget down to the 100-entry minimum");
        } finally {
            TestUtils.standardTearDown();
        }
    }

    private static final int NEIGHBOURS = 5;

    private static JsonVector vectorOf(double[] components) {
        return new JsonVector(components);
    }

    private static void seedNeighbourhood(double[] query) throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var cache = IocContainer.get(Cache.class);
        final var random = new Random(42);
        for (var i = 0; i < 400; i++) {
            final var components = new double[query.length];
            for (var d = 0; d < components.length; d++) {
                components[d] = random.nextGaussian();
            }
            cacheVector(cache, "far" + i, vectorOf(components));
        }
        for (var i = 0; i < NEIGHBOURS; i++) {
            final var components = query.clone();
            components[i] += 1e-9 * (i + 1);
            cacheVector(cache, "near" + i, vectorOf(components));
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "embedding");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("embedding"));
    }

    private static void cacheVector(Cache cache, String id, JsonVector vector) throws Exception {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("embedding", vector);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private static List<String> nearestIds(JsonVector query, boolean exact) throws Exception {
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(NEIGHBOURS));
        args.add("exact", JsonBoolean.of(exact));
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(
                List.of(new FilterAggregationStep(new CustomOperator("nearest", "embedding", query, args))));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    @Test
    public void test_nearest_unchanged_by_signature_keys() throws Exception {
        final double[] components = {0.3, -1.2, 0.8, 2.0, -0.5, 1.1, -0.7, 0.4};
        seedNeighbourhood(components);
        try {
            final var query = vectorOf(components);
            final var candidates = VectorSimilarityIndexHelper.candidateIds(
                    new CustomOperator("nearest", "embedding", query, nearestArgs(query)), TestGlobals.DB,
                    TestGlobals.COLL, ReadObserver.NONE);

            assertNotNull(candidates);
            assertTrue(candidates.size() >= 100 && candidates.size() < 405, String.valueOf(candidates.size()));
            for (var i = 0; i < NEIGHBOURS; i++) {
                assertTrue(candidates.contains("near" + i));
            }
            final var viaIndex = nearestIds(query, false);
            assertEquals(nearestIds(query, true), viaIndex);
            assertEquals(Set.of("near0", "near1", "near2", "near3", "near4"), Set.copyOf(viaIndex));
        } finally {
            TestUtils.standardTearDown();
        }
    }

    private static JsonObject nearestArgs(JsonVector query) {
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(NEIGHBOURS));
        args.add("exact", JsonBoolean.of(false));
        return args;
    }
}
