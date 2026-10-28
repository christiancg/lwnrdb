package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.FilterOperatorHelper;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FilterConjunctionMemoryTest {
    private static final long ROUNDS_SEED = 13;

    private static final int DOCUMENTS = 300;
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void cacheCollection() throws IOException {
        final var random = new Random(11);
        for (var i = 0; i < DOCUMENTS; i++) {
            final var doc = new JsonObject();
            doc.addProperty(Globals.PK_FIELD, "d" + i);
            doc.addProperty("n", random.nextInt(20));
            doc.addProperty("m", random.nextInt(5));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, doc);
            entry.set_id("d" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        cache.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "d0", 0, 100, 0));
    }

    private static List<BaseOperator> children(Random random, int count) {
        final var types = List.of(FieldOperatorType.GREATER_THAN_EQUALS, FieldOperatorType.SMALLER_THAN,
                FieldOperatorType.NOT_EQUALS, FieldOperatorType.EQUALS);
        final var children = new ArrayList<BaseOperator>();
        for (var i = 0; i < count; i++) {
            final var field = random.nextBoolean() ? "n" : "m";
            final var bound = field.equals("n") ? random.nextInt(20) : random.nextInt(5);
            children.add(new FieldOperator(types.get(random.nextInt(types.size())), field, new JsonNumber(bound)));
        }
        return children;
    }

    private static List<List<BaseOperator>> rounds() {
        final var random = new Random(ROUNDS_SEED);
        final var rounds = new ArrayList<List<BaseOperator>>();
        for (var round = 0; round < 40; round++) {
            rounds.add(children(random, 1 + random.nextInt(round < 20 ? 3 : 60)));
        }
        return rounds;
    }

    private List<Stream<JsonObject>> childStreams(List<BaseOperator> children) throws IOException {
        final var streams = new ArrayList<Stream<JsonObject>>();
        for (var child : children) {
            streams.add(FilterOperatorHelper.processOperator(child, null, TestGlobals.DB, TestGlobals.COLL));
        }
        return streams;
    }

    private Stream<JsonObject> wholeCollection() {
        return cache.getWholeCollection(TestGlobals.DB, TestGlobals.COLL).values().stream().map(DbEntry::getData);
    }

    private List<JsonObject> legacy(ConjunctionOperatorType type, List<BaseOperator> children) throws IOException {
        final var streams = childStreams(children);
        return switch (type) {
            case AND -> LegacyConjunctionOracle.andXor(streams, children.size()).toList();
            case XOR -> LegacyConjunctionOracle.andXor(streams, 1).toList();
            case NOR ->
                LegacyConjunctionOracle.norNand(LegacyConjunctionOracle.or(streams), wholeCollection()).toList();
            case NAND -> LegacyConjunctionOracle
                    .norNand(LegacyConjunctionOracle.andXor(streams, children.size()), wholeCollection()).toList();
            case OR -> LegacyConjunctionOracle.or(streams).toList();
        };
    }

    private static List<String> ids(List<JsonObject> rows) {
        return rows.stream().map(row -> row.get(Globals.PK_FIELD).asJsonString().getValue()).sorted().toList();
    }

    @Test
    public void everyConjunctionAnswersLikeTheGroupingImplementation() throws IOException {
        cacheCollection();
        final var rounds = rounds();
        var nonEmpty = 0;
        for (var round = 0; round < rounds.size(); round++) {
            final var children = rounds.get(round);
            for (var type : List.of(ConjunctionOperatorType.AND, ConjunctionOperatorType.XOR,
                    ConjunctionOperatorType.NOR, ConjunctionOperatorType.NAND)) {
                final var expected = ids(legacy(type, children));

                final var actual = ids(FilterOperatorHelper.processOperator(new ConjunctionOperator(type, children),
                        null, TestGlobals.DB, TestGlobals.COLL).toList());

                assertEquals(expected, actual, type + " round " + round);
                nonEmpty += actual.isEmpty() ? 0 : 1;
            }
        }
        assertTrue(nonEmpty > 0);
    }
}
