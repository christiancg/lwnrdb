package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.bckg_ops.events.EntityEvent;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cache.IndexIdCounts;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.ops.index.IndexedValue;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateIndexRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexEntryReaderSortCoverageTest {
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;
    private static final String FIELD = "score";
    private static final int DOCUMENTS = 200;
    private static final Comparator<FieldIndexEntry<?>> ASCENDING = Comparator
            .<FieldIndexEntry<?>>comparingInt(entry -> entry.getValue() instanceof Number ? 0 : 1)
            .thenComparing(entry -> entry.getValue() instanceof Number number
                    ? String.format("%020.3f", number.doubleValue() + 1e6)
                    : entry.getValue().toString());

    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final IndexIdCounts idCounts = IocContainer.get(IndexIdCounts.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration configuration = Configuration.getInstance();
    private Long savedMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        final var documents = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            documents.add(document("d" + i, i % 50));
        }
        final var bulk = new BulkSaveRequest(DB, COLL);
        bulk.setObjects(documents);
        assertEquals(OperationStatus.OK, processor.processMessage(bulk).getStatus());
        processor.processMessage(new CreateIndexRequest(DB, COLL, FIELD));
        drain();
    }

    @AfterEach
    public void tearDown() throws Exception {
        idCounts.forgetDatabase(DB);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, Object score) {
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString(id));
        document.add(FIELD, score instanceof Number number ? new JsonNumber(number) : new JsonString((String) score));
        return document;
    }

    private void save(JsonObject document) {
        final var request = new SaveRequest(DB, COLL);
        request.setObject(document);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void drain() throws IOException, InterruptedException {
        final var pending = IocContainer.get(PendingIndexWrites.class);
        for (final var id : pending.idsFor(DB, COLL)) {
            final var entries = IocContainer.get(Cache.class).getEntriesByIds(DB, COLL, Set.of(id));
            if (!entries.isEmpty()) {
                EventProcessorHelper.processEvent(new EntityEvent(EventType.CREATED, DB, COLL, entries.getFirst()));
            }
        }
        assertTrue(pending.idsFor(DB, COLL).isEmpty());
    }

    private static String identifier(Class<?> type) {
        return Cache.getIndexIdentifier(FIELD, type);
    }

    private long known(Class<?> type) {
        return idCounts.known(DB, COLL, identifier(type));
    }

    private static List<String> sorted() throws IOException {
        return IndexEntryReader.sortedIdsForField(DB, COLL, FIELD, ASCENDING, Long.MAX_VALUE, ReadObserver.NONE);
    }

    @Test
    public void test_a_sorted_walk_counts_entries_once_across_queries() throws Exception {
        assertEquals(DOCUMENTS, sorted().size());
        assertEquals(DOCUMENTS, known(Number.class));

        idCounts.forget(DB, COLL, identifier(Number.class));
        idCounts.count(DB, COLL, identifier(Number.class), () -> DOCUMENTS - 1L);

        assertNull(sorted(), "the coverage gate reads the remembered count instead of summing the entries");
        idCounts.forget(DB, COLL, identifier(Number.class));
        assertEquals(DOCUMENTS, sorted().size());
        assertEquals(DOCUMENTS, known(Number.class));
    }

    @Test
    public void test_a_batch_flush_adjusts_the_count() throws Exception {
        sorted();
        save(document("extra", -1));
        save(document("d3", 1000));
        save(document("named", "n1"));
        drain();

        assertEquals(DOCUMENTS + 1L, known(Number.class), "the batches adjusted the count without a recount");
        assertEquals(IndexIdCounts.UNKNOWN, known(String.class), "a type never counted is not invented by a batch");
        final var ids = sorted();
        assertNotNull(ids);
        assertEquals(DOCUMENTS + 2, ids.size());
        assertEquals("extra", ids.getFirst());
        assertEquals("d3", ids.get(DOCUMENTS));
        assertEquals("named", ids.getLast());
        assertEquals(1L, known(String.class));
    }

    @Test
    public void test_an_inexact_batch_forces_one_recount() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        IocContainer.get(Cache.class).evictCollection(DB, COLL);
        assertEquals(DOCUMENTS, sorted().size());
        assertEquals(DOCUMENTS, known(Number.class));
        save(document("extra", 7));
        locks.lockRead(DB, COLL);
        locks.lockIndex(DB, COLL, FIELD);
        try {
            final var batch = FieldIndexBatch.open(DB, COLL, FIELD, List.of("extra"),
                    Map.of("extra", Collections.singletonList(IndexedValue.of(JsonNull.INSTANCE))));
            batch.apply("extra", new JsonNumber(7));
            batch.flush();
        } finally {
            locks.releaseIndex(DB, COLL, FIELD);
            locks.releaseRead(DB, COLL);
        }

        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class), "a log-only batch cannot know its delta");
        drain();
        assertEquals(DOCUMENTS + 1, sorted().size());
        assertEquals(DOCUMENTS + 1L, known(Number.class));
    }

    @Test
    public void test_a_hash_kind_on_the_sort_field_declines_without_counting() throws Exception {
        final var nested = new JsonObject();
        nested.addProperty("k", 1);
        final var document = new JsonObject();
        document.add(Globals.PK_FIELD, new JsonString("nested"));
        document.add(FIELD, nested);
        save(document);
        drain();
        idCounts.forget(DB, COLL, identifier(Number.class));

        assertNull(sorted());
        assertEquals(IndexIdCounts.UNKNOWN, known(Number.class));
    }
}
