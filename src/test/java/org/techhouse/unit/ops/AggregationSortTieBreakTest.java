package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AggregationSortTieBreakTest {
    private static final String FIELD = "meta";
    private Cache cache;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void insertObjectValued(String id, int inner) throws IOException {
        final var nested = new JsonObject();
        nested.addProperty("x", inner);
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(FIELD, nested);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void enableIndex() throws InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    private List<String> runIds(BaseAggregationStep... steps) throws IOException {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(steps));
        return AggregationOperationHelper.processAggregation(request).stream()
                .map(o -> o.get(Globals.PK_FIELD).asJsonString().getValue()).toList();
    }

    private void seedOutOfIdOrder() throws IOException {
        insertObjectValued("z", 1);
        insertObjectValued("a", 2);
        insertObjectValued("m", 3);
    }

    @Test
    public void test_object_sort_keys_order_by_id_on_the_index_path_as_on_the_scan() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, true));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("a", "m", "z"), viaScan, "the scan breaks ties on _id");
        assertEquals(viaScan, viaIndex,
                "every object value ties, so the index path concatenated whole entries in hash-bucket order"
                        + " and disagreed with the scan");
    }

    @Test
    public void test_a_limited_object_sort_returns_the_same_page_either_way() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, true), new LimitAggregationStep(2));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true), new LimitAggregationStep(2));

        assertEquals(List.of("a", "m"), viaScan);
        assertEquals(viaScan, viaIndex, "with LIMIT the tie-break decides the result set, not merely its order");
    }

    @Test
    public void test_descending_object_sort_also_agrees() throws Exception {
        seedOutOfIdOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, false));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, false));

        assertEquals(viaScan, viaIndex, "the tie-break must not depend on the sort direction");
    }

    private void insertNumberValued(String id, double value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(FIELD, new JsonNumber(value));
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    @Test
    public void test_negative_zero_sorts_the_same_way_on_both_paths() throws Exception {
        insertNumberValued("z", -0.0);
        insertNumberValued("a", 0.0);
        final var viaScan = runIds(new SortAggregationStep(FIELD, true));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("a", "z"), viaScan,
                "the index keys both spellings as 0, so the scan must tie them and break on _id too");
        assertEquals(viaScan, viaIndex);
    }
}
