package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregationOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;
import org.techhouse.ops.req.agg.step.SortAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.JsonUtils;

public class AggregationSortCustomOrderTest {
    private static final String FIELD = "marker";
    private static final String LOWEST_GEO = "#geo(8.0,5.0)";
    private static final String MIDDLE_GEO = "#geo(9.0,5.0)";
    private static final String HIGHEST_GEO = "#geo(10.0,5.0)";
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

    private void insertValue(String id, JsonString value) throws IOException {
        final var obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        entry.set_id(id);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
    }

    private void insertGeo(String id, String wireValue) throws IOException {
        insertValue(id, new JsonGeo(wireValue));
    }

    private void insertDateTime(String id, String isoValue) throws IOException {
        insertValue(id, new JsonDateTime("#datetime(" + isoValue + ")"));
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

    private void seedGeoOutOfTextOrder() throws IOException {
        insertGeo("x", HIGHEST_GEO);
        insertGeo("y", MIDDLE_GEO);
        insertGeo("z", LOWEST_GEO);
    }

    @Test
    public void test_geo_sort_uses_the_same_order_as_the_range_filter() throws Exception {
        seedGeoOutOfTextOrder();

        final var sorted = runIds(new SortAggregationStep(FIELD, true));
        final var above = runIds(new FilterAggregationStep(
                new FieldOperator(FieldOperatorType.GREATER_THAN, FIELD, new JsonGeo(MIDDLE_GEO))));

        assertEquals(List.of("z", "y", "x"), sorted,
                "SORT ordered by wire text, which puts #geo(10.0,5.0) first and contradicts GREATER_THAN");
        assertEquals(List.of("x"), above, "only the highest point is above the middle one");
    }

    @Test
    public void test_geo_sort_is_the_same_indexed_and_scanned() throws Exception {
        seedGeoOutOfTextOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, true));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(viaScan, viaIndex);
        assertEquals(List.of("x", "y", "z"), runIds(new SortAggregationStep(FIELD, false)));
    }

    private void seedEqualInstantsOutOfIdOrder() throws IOException {
        insertDateTime("b", "2024-01-01T10:00");
        insertDateTime("a", "2024-01-01T10:00:00");
        insertDateTime("c", "2024-06-01T10:00:00");
    }

    @Test
    public void test_equal_datetimes_break_on_id_on_both_paths() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, true));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("a", "b", "c"), viaScan,
                "the two spellings are the same instant, so the tie breaks on _id");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_equal_datetimes_tie_on_the_sorted_ids_index_path() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        enableIndex();

        final var orderedIds = IndexHelper.getSortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD,
                (left, right) -> JsonUtils.compareSortKeysAscending(IndexHelper.indexValueToElement(left.getValue()),
                        IndexHelper.indexValueToElement(right.getValue())),
                Long.MAX_VALUE);

        assertNotNull(orderedIds, "a collection of scalar custom values has no hash index, so this path applies");
        assertEquals(List.of("a", "b", "c"), orderedIds,
                "two entries holding the same instant are one run and their ids merge sorted");
    }

    @Test
    public void test_a_limited_datetime_sort_returns_the_same_page_either_way() throws Exception {
        seedEqualInstantsOutOfIdOrder();
        final var viaScan = runIds(new SortAggregationStep(FIELD, true), new LimitAggregationStep(1));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true), new LimitAggregationStep(1));

        assertEquals(List.of("a"), viaScan, "with LIMIT the tie-break decides the result set, not merely its order");
        assertEquals(viaScan, viaIndex);
    }

    @Test
    public void test_custom_values_sort_after_plain_strings_on_both_paths() throws Exception {
        insertValue("s", new JsonString("zzz"));
        insertGeo("g", LOWEST_GEO);
        final var viaScan = runIds(new SortAggregationStep(FIELD, true));
        enableIndex();

        final var viaIndex = runIds(new SortAggregationStep(FIELD, true));

        assertEquals(List.of("s", "g"), viaScan, "a custom value ranks above every plain string");
        assertEquals(viaScan, viaIndex);
    }
}
