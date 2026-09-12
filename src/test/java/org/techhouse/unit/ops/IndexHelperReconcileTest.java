package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperReconcileTest {
    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private DbEntry entryWith(String id, String field, JsonBaseElement value) {
        JsonObject obj = new JsonObject();
        obj.add(Globals.PK_FIELD, new JsonString(id));
        obj.add(field, value);
        DbEntry e = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
        e.set_id(id);
        return e;
    }

    private void setupCollection(Cache cache, DbEntry... entries) {
        final var adminCollEntry = new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL);
        final var pk = new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x", 0, 100, 0);
        cache.putAdminCollectionEntry(adminCollEntry, pk);
        for (var entry : entries) {
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
    }

    @Test
    public void test_index_value_to_element_for_all_value_kinds() {
        // Integral numbers normalize so they compare/hash equal to a document-read integer
        final var numberElement = IndexHelper.indexValueToElement(42.0);
        assertTrue(numberElement.isJsonNumber());
        assertEquals(42, numberElement.asJsonNumber().asInteger());
        assertEquals(new JsonNumber(42), numberElement);

        final var doubleElement = IndexHelper.indexValueToElement(5.5);
        assertTrue(doubleElement.isJsonNumber());
        assertEquals(5.5, doubleElement.asJsonNumber().getValue().doubleValue());

        final var stringElement = IndexHelper.indexValueToElement("hello");
        assertTrue(stringElement.isJsonString());
        assertEquals("hello", stringElement.asJsonString().getValue());

        final var booleanElement = IndexHelper.indexValueToElement(Boolean.TRUE);
        assertTrue(booleanElement.isJsonBoolean());
        assertTrue(booleanElement.asJsonBoolean().getValue());

        final var custom = new JsonTime("#time(10:00:00)");
        assertSame(custom, IndexHelper.indexValueToElement(custom));

        assertSame(JsonNull.INSTANCE, IndexHelper.indexValueToElement(null));
        assertSame(JsonNull.INSTANCE, IndexHelper.indexValueToElement(JsonNull.INSTANCE));
    }

    @Test
    public void test_reconcilePending_null_value_does_not_force_full_scan() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        final var indexed = entryWith("s1", "status", new JsonString("active"));
        final var pending = entryWith("n1", "status", JsonNull.INSTANCE);
        setupCollection(cache, indexed, pending);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status"));

        final var pendingWrites = IocContainer.get(org.techhouse.bckg_ops.PendingIndexWrites.class);
        pendingWrites.mark(TestGlobals.DB, TestGlobals.COLL, "n1");

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "status");

        assertNotNull(entries, "null-valued pending doc must not force a full-scan fallback (null result)");
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(allIds.contains("n1"), "id of the null-valued pending doc must appear in the reconciled result");
    }

    @Test
    public void test_element_to_lookup_value_converts_primitives() {
        final var numResult = IndexHelper.elementToLookupValue(new JsonNumber(42));
        assertNotNull(numResult);
        assertInstanceOf(Number.class, numResult);
        assertEquals(42.0, ((Number) numResult).doubleValue());

        final var strResult = IndexHelper.elementToLookupValue(new JsonString("hello"));
        assertEquals("hello", strResult);

        final var boolResult = IndexHelper.elementToLookupValue(new JsonBoolean(true));
        assertEquals(Boolean.TRUE, boolResult);

        assertNull(IndexHelper.elementToLookupValue(JsonNull.INSTANCE));
        assertNull(IndexHelper.elementToLookupValue(null));
        assertNull(IndexHelper.elementToLookupValue(new JsonObject()));
        assertNull(IndexHelper.elementToLookupValue(new JsonArray()));
    }

    @Test
    public void test_reconcilePending_doc_missing_field_is_skipped() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        final var indexed = entryWith("has1", "status", new JsonString("active"));
        final var missingFieldDoc = new JsonObject();
        missingFieldDoc.add(Globals.PK_FIELD, new JsonString("missing1"));
        final var pendingEntry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, missingFieldDoc);
        pendingEntry.set_id("missing1");
        setupCollection(cache, indexed, pendingEntry);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status"));

        IocContainer.get(org.techhouse.bckg_ops.PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL,
                "missing1");

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "status");
        assertNotNull(entries);
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(allIds.contains("missing1"));
        assertTrue(allIds.contains("has1"));
    }

    // reconcilePending adds a second pending null-valued document to the null entry created by the
    // first one processed in the same reconciliation pass (null entries are never preloaded from the
    // index, only ever created while reconciling pending writes)
    @Test
    public void test_reconcilePending_second_null_value_joins_first_pending_null_entry() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("s1", "status", new JsonString("active")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "status");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("status"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("null1", "status", JsonNull.INSTANCE));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("null2", "status", JsonNull.INSTANCE));
        final var pendingWrites = IocContainer.get(org.techhouse.bckg_ops.PendingIndexWrites.class);
        pendingWrites.mark(TestGlobals.DB, TestGlobals.COLL, "null1");
        pendingWrites.mark(TestGlobals.DB, TestGlobals.COLL, "null2");

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "status");
        assertNotNull(entries);
        final var nullEntry = entries.stream().filter(e -> e.getValue() == JsonNull.INSTANCE).findFirst().orElseThrow();
        assertEquals(Set.of("null1", "null2"), nullEntry.getIds());
    }

    @Test
    public void test_reconcilePending_new_boolean_value_creates_entry_via_scalarEntryFor() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("b1", "flag", new JsonBoolean(true)));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "flag");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("flag"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entryWith("b2", "flag", new JsonBoolean(false)));
        IocContainer.get(org.techhouse.bckg_ops.PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL, "b2");

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "flag");
        assertNotNull(entries);
        assertEquals(2, entries.size());
        final var falseEntry = entries.stream().filter(e -> Boolean.FALSE.equals(e.getValue())).findFirst()
                .orElseThrow();
        assertEquals(Set.of("b2"), falseEntry.getIds());
    }

    @Test
    public void test_reconcilePending_new_custom_value_creates_entry_via_scalarEntryFor() throws IOException {
        Cache cache = IocContainer.get(Cache.class);
        setupCollection(cache, entryWith("ct1", "startTime", new JsonTime("#time(08:00:00)")));
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "startTime");
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of("startTime"));

        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                entryWith("ct2", "startTime", new JsonTime("#time(09:00:00)")));
        IocContainer.get(org.techhouse.bckg_ops.PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL, "ct2");

        final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, "startTime");
        assertNotNull(entries);
        assertEquals(2, entries.size());
        final var allIds = entries.stream().flatMap(e -> e.getIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("ct1", "ct2"), allIds);
    }

    @Test
    public void test_element_to_lookup_value_returns_custom_instance_itself() {
        final var custom = new JsonTime("#time(10:00:00)");
        assertSame(custom, IndexHelper.elementToLookupValue(custom));
    }
}
