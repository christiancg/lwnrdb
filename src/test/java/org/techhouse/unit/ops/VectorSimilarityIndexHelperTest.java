package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.VectorSimilarityIndexHelper;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.test.IndexRaceSupport;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class VectorSimilarityIndexHelperTest {
    @Test
    public void test_non_nearest_operator_is_not_index_accelerable() throws Exception {
        final var op = new CustomOperator("mystery", "embedding", null, new JsonObject());

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll"));
    }

    @Test
    public void test_exact_query_is_not_index_accelerable() throws Exception {
        final var query = new JsonVector("#vector(1.0,0.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(5));
        args.add("exact", new JsonBoolean(true));
        final var op = new CustomOperator("nearest", "embedding", query, args);

        assertNull(VectorSimilarityIndexHelper.candidateIds(op, "db", "coll"));
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
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "embedding");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("embedding"));
    }

    private static CustomOperator nearest() {
        final var query = new JsonVector("#vector(0.5,1.0)");
        final var args = new JsonObject();
        args.add("value", query);
        args.add("k", new JsonNumber(5));
        args.add("exact", new JsonBoolean(false));
        return new CustomOperator("nearest", "embedding", query, args);
    }

    @Test
    public void test_neighbourhood_collect_holds_the_field_lock() throws Exception {
        seedVectorIndex();
        try {
            final var operator = nearest();
            IndexRaceSupport.readWhileTheIndexerChurns(TestGlobals.DB, TestGlobals.COLL, "embedding", JsonVector.class,
                    () -> VectorSimilarityIndexHelper.candidateIds(operator, TestGlobals.DB, TestGlobals.COLL));
        } finally {
            TestUtils.standardTearDown();
        }
    }
}
