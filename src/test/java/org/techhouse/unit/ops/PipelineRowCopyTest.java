package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.mid_operators.ArrayParamMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.ops.req.agg.step.JoinAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class PipelineRowCopyTest {
    private static final EJson E_JSON = new EJson();
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void cacheDoc(String collName, JsonObject doc) throws IOException {
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, collName, doc);
        entry.set_id(doc.get(Globals.PK_FIELD).asJsonString().getValue());
        TestUtils.cacheEntry(cache, TestGlobals.DB, collName, entry);
    }

    private JsonObject cachedDoc(String collName, String id) {
        return cache.getWholeCollection(TestGlobals.DB, collName).get(id).getData();
    }

    private static List<JsonObject> run(List<BaseAggregationStep> steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(steps);
        return AggregationOperationHelper.processAggregation(request);
    }

    private static MapOperator concat(String field, String... operands) {
        final var array = new JsonArray();
        for (var operand : operands) {
            array.add(new JsonString(operand));
        }
        return new AddFieldMapOperator(field, null, new ArrayParamMidOperator(MidOperationType.CONCAT, array));
    }

    private static MapOperator sizeOfJoined(String field) {
        return new AddFieldMapOperator(field, null, new OneParamMidOperator(MidOperationType.SIZE, "j"));
    }

    @Test
    public void copyForWritesSharesUnwrittenRoots() {
        final var written = new JsonObject();
        written.addProperty("x", 1);
        final var shared = new JsonArray();
        shared.add(new JsonString("v"));
        final var source = new JsonObject();
        source.addProperty(Globals.PK_FIELD, "a");
        source.add("written", written);
        source.add("shared", shared);

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(List.of("written.y")));

        assertSame(shared, copy.get("shared"));
        assertSame(source.get(Globals.PK_FIELD), copy.get(Globals.PK_FIELD));
        assertNotSame(written, copy.get("written"));
        assertEquals(written, copy.get("written"));
        copy.get("written").asJsonObject().addProperty("y", 2);
        assertFalse(written.has("y"));
    }

    @Test
    public void copyForWritesOfMissingRootIsHarmless() {
        final var source = new JsonObject();
        source.addProperty("a", 1);

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(List.of("missing")));

        assertEquals(source, copy);
        assertEquals(1, copy.size());
        assertFalse(copy.has("missing"));
    }

    private void cacheJoinFixture() throws IOException {
        for (var i = 0; i < 5; i++) {
            final var left = new JsonObject();
            left.addProperty(Globals.PK_FIELD, "l" + i);
            left.addProperty("ref", 7);
            cacheDoc(TestGlobals.COLL, left);
        }
        for (var i = 0; i < 2; i++) {
            final var right = new JsonObject();
            right.addProperty(Globals.PK_FIELD, "r" + i);
            right.addProperty("key", 7);
            cacheDoc(TestGlobals.JOIN_COLL, right);
        }
    }

    private static BaseAggregationStep join() {
        return new JoinAggregationStep(TestGlobals.JOIN_COLL, "ref", "key", "j");
    }

    @Test
    public void mapAfterJoinSharesTheJoinedArray() throws IOException {
        cacheJoinFixture();
        final var rightBefore = cachedDoc(TestGlobals.JOIN_COLL, "r0").deepCopy();

        final var unwritten = run(List.of(join(), new MapAggregationStep(List.of(sizeOfJoined("x")))));

        assertEquals(5, unwritten.size());
        final var lookup = unwritten.getFirst().get("j");
        for (var row : unwritten) {
            assertSame(lookup, row.get("j"));
            assertEquals(2.0, row.get("x").asJsonNumber().getValue().doubleValue());
        }

        final var written = run(List.of(join(), new MapAggregationStep(List.of(sizeOfJoined("j.extra")))));

        final var seen = new ArrayList<>();
        for (var row : written) {
            final var joined = row.get("j");
            assertTrue(joined.isJsonObject());
            assertEquals(2.0, joined.asJsonObject().get("extra").asJsonNumber().getValue().doubleValue());
            seen.forEach(other -> assertNotSame(other, joined));
            seen.add(joined);
        }
        final var rejoined = run(List.of(join()));
        assertEquals(2, rejoined.getFirst().get("j").asJsonArray().size());
        assertEquals(rightBefore, cachedDoc(TestGlobals.JOIN_COLL, "r0"));
    }

    @Test
    public void mapNeverMutatesTheCachedDocument() throws IOException {
        final var deep = new JsonObject();
        deep.addProperty("d", "x");
        final var shared = new JsonObject();
        shared.addProperty("b", 1);
        shared.add("c", deep);
        final var doc = new JsonObject();
        doc.addProperty(Globals.PK_FIELD, "doc");
        doc.add("a", shared);
        doc.addProperty("top", "t");
        doc.addProperty("s", "hello");
        doc.addProperty("untouched", 3);
        cacheDoc(TestGlobals.COLL, doc);
        final var before = E_JSON.toJson(doc);

        final var result = run(
                List.of(new MapAggregationStep(List.of(concat("a.c.e", "-lit"), new RemoveFieldMapOperator("a.b", null),
                        new RemoveFieldMapOperator("top", null), concat("s", "s", "-!")))));

        final var row = result.getFirst();
        assertEquals("lit", row.get("a").asJsonObject().get("c").asJsonObject().get("e").asJsonString().getValue());
        assertFalse(row.get("a").asJsonObject().has("b"));
        assertFalse(row.has("top"));
        assertEquals("hello!", row.get("s").asJsonString().getValue());
        assertSame(doc.get("untouched"), row.get("untouched"));
        assertEquals(before, E_JSON.toJson(cachedDoc(TestGlobals.COLL, "doc")));
        assertEquals(before, E_JSON.toJson(doc));
    }

    private static JsonObject body(String id, int variant) {
        final var nested = new JsonObject();
        nested.addProperty("v", variant);
        final var row = new JsonObject();
        row.addProperty(Globals.PK_FIELD, id);
        row.addProperty("kind", "k" + variant);
        row.add("nested", nested);
        return row;
    }

    @Test
    public void distinctWithoutFieldDoesNotCopyDuplicates() throws IOException {
        final var source = new ArrayList<JsonObject>();
        for (var i = 0; i < 1000; i++) {
            source.add(body("id" + i, (i * 7) % 10));
        }

        final var result = AggregationOperationHelper.processStepsOnStream(List.of(new DistinctAggregationStep(null)),
                source.stream());

        assertEquals(10, result.size());
        final var kinds = result.stream().map(row -> row.get("kind").asJsonString().getValue()).toList();
        assertEquals(List.of("k0", "k7", "k4", "k1", "k8", "k5", "k2", "k9", "k6", "k3"), kinds);
        for (var i = 0; i < 10; i++) {
            final var row = result.get(i);
            assertFalse(row.has(Globals.PK_FIELD));
            assertSame(source.get(i).get("nested"), row.get("nested"));
        }
        assertTrue(source.stream().allMatch(row -> row.has(Globals.PK_FIELD)));
    }

    private static List<JsonObject> legacyDistinct(List<JsonObject> source) {
        return source.stream().map(jsonObject -> {
            final var result = jsonObject.deepCopy();
            if (result.has(Globals.PK_FIELD)) {
                result.remove(Globals.PK_FIELD);
            }
            return result;
        }).distinct().toList();
    }

    private static JsonObject randomRow(Random random, int index) {
        final var row = new JsonObject();
        final var keys = new ArrayList<>(List.of("a", "b", "c"));
        if (random.nextBoolean()) {
            row.addProperty(Globals.PK_FIELD, "id" + index);
        }
        Collections.shuffle(keys, random);
        for (var key : keys) {
            switch (random.nextInt(5)) {
                case 0 -> row.add(key, new JsonNumber(random.nextInt(2)));
                case 1 -> row.add(key, new JsonNumber(random.nextInt(2) + 0.0));
                case 2 -> row.add(key, new JsonString("s" + random.nextInt(2)));
                case 3 -> {
                    final var nested = new JsonObject();
                    nested.addProperty("n", random.nextInt(2));
                    row.add(key, nested);
                }
                default -> {
                }
            }
        }
        return row;
    }

    @Test
    public void distinctWithoutFieldMatchesOldSemantics() throws IOException {
        for (var seed = 0; seed < 50; seed++) {
            final var random = new Random(seed);
            final var source = new ArrayList<JsonObject>();
            for (var i = 0; i < 200; i++) {
                source.add(randomRow(random, i));
            }
            final var expected = legacyDistinct(source).stream().map(E_JSON::toJson).toList();

            final var actual = AggregationOperationHelper
                    .processStepsOnStream(List.of(new DistinctAggregationStep(null)), source.stream()).stream()
                    .map(E_JSON::toJson).toList();

            assertEquals(expected, actual, "seed " + seed);
        }
    }
}
