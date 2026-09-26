package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.GeoSpatialIndexHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.test.IndexRaceSupport;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class GeoSpatialIndexHelperTest {
    @Test
    public void test_distance_greater_than_is_not_index_accelerable() throws Exception {
        final var target = new JsonGeo("#geo(40.0,-74.0)");
        final var args = new JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString("GREATER_THAN"));
        args.add("distance", new JsonNumber(1000));
        final var op = new CustomOperator("distance", "location", target, args);

        assertNull(GeoSpatialIndexHelper.candidateIds(op, "db", "coll"));
    }

    @Test
    public void test_unknown_custom_operator_is_not_index_accelerable() throws Exception {
        final var op = new CustomOperator("mystery", "location", null, new JsonObject());

        assertNull(GeoSpatialIndexHelper.candidateIds(op, "db", "coll"));
    }

    private static void seedGeoIndex() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var cache = IocContainer.get(Cache.class);
        for (var i = 0; i < 40; i++) {
            final var object = new JsonObject();
            object.add(Globals.PK_FIELD, new JsonString("g" + i));
            object.add("location", new JsonGeo("#geo(40." + i + ",-74.0)"));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
            entry.set_id("g" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "location");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("location"));
    }

    private static CustomOperator withinRadius() {
        final var target = new JsonGeo("#geo(40.5,-74.0)");
        final var args = new JsonObject();
        args.add("value", target);
        args.add("comparator", new JsonString("SMALLER_THAN"));
        args.add("distance", new JsonNumber(500_000));
        return new CustomOperator("distance", "location", target, args);
    }

    @Test
    public void test_bounding_box_collect_holds_the_field_lock() throws Exception {
        seedGeoIndex();
        try {
            final var operator = withinRadius();
            IndexRaceSupport.readWhileTheIndexerChurns(TestGlobals.DB, TestGlobals.COLL, "location", JsonGeo.class,
                    () -> assertNotNull(
                            GeoSpatialIndexHelper.candidateIds(operator, TestGlobals.DB, TestGlobals.COLL)));
        } finally {
            TestUtils.standardTearDown();
        }
    }
}
