package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.DroppedCollections;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScanAdmissionTest {
    private static final List<String> IDS = List.of("c", "a", "e", "b", "d");
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration config = Configuration.getInstance();
    private long originalMaxMemory;
    private MemoryManagement originalMemoryManagement;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalMaxMemory = config.getMaxMemoryBytes();
        originalMemoryManagement = TestUtils.getPrivateField(cache, "memoryManagement", MemoryManagement.class);
        for (final var id : IDS) {
            save(TestGlobals.COLL, id, "value-" + id);
        }
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
    }

    private static void save(String collName, String id, String payload) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", payload);
        final var request = new SaveRequest(TestGlobals.DB, collName);
        request.setObject(object);
        request.set_id(id);
        assertNotNull(SaveOperationHelper.executeSave(request));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", originalMaxMemory);
        TestUtils.setPrivateField(cache, "memoryManagement", originalMemoryManagement);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private <T> T locked(ScanBody<T> body) throws Exception {
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            return body.run();
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private interface ScanBody<T> {
        T run() throws Exception;
    }

    private List<String> scanned(Function<Stream<DbEntry>, Stream<DbEntry>> shape) throws IOException {
        try (var stream = cache.streamCollection(TestGlobals.DB, TestGlobals.COLL)) {
            return shape.apply(stream).map(DbEntry::get_id).toList();
        }
    }

    private Integer resident() {
        final var cached = cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL);
        return cached == null ? null : cached.size();
    }

    @Test
    public void test_a_locked_full_scan_admits_the_collection() throws Exception {
        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());

        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_locked_scan_drained_one_element_at_a_time_admits_too() throws Exception {
        final var drained = locked(() -> {
            final var ids = new ArrayList<String>();
            try (var stream = cache.streamCollection(TestGlobals.DB, TestGlobals.COLL)) {
                stream.iterator().forEachRemaining(entry -> ids.add(entry.get_id()));
            }
            return ids;
        });

        assertEquals(IDS.size(), drained.size());
        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_scan_in_scan_order_admits_and_keeps_its_order() throws Exception {
        final var ordered = locked(() -> {
            try (var stream = cache.streamCollectionInScanOrder(TestGlobals.DB, TestGlobals.COLL)) {
                return stream.map(DbEntry::get_id).toList();
            }
        });

        assertEquals(IDS.stream().sorted().toList(), ordered);
        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_scan_cut_by_a_limit_does_not_admit() throws Exception {
        assertEquals(2, locked(() -> scanned(stream -> stream.limit(2))).size());

        assertNull(resident());
    }

    @Test
    public void test_a_scan_cut_by_an_exception_does_not_admit() {
        assertThrows(IllegalStateException.class, () -> locked(() -> scanned(stream -> stream.peek(entry -> {
            if (entry.get_id().equals(IDS.get(2))) {
                throw new IllegalStateException("downstream failure");
            }
        }))));

        assertNull(resident());
    }

    @Test
    public void test_a_scan_cut_by_the_aggregate_timeout_does_not_admit() {
        final var seen = new int[1];
        assertThrows(AggregateLimitException.class, () -> locked(() -> scanned(stream -> stream.peek(_ -> {
            if (++seen[0] == IDS.size()) {
                throw new AggregateLimitException(ErrorCode.AGGREGATE_TIMEOUT);
            }
        }))));

        assertNull(resident());
    }

    @Test
    public void test_a_dirty_read_does_not_admit() throws Exception {
        assertEquals(IDS.size(), scanned(Function.identity()).size());

        assertNull(resident());
    }

    @Test
    public void test_a_scan_over_the_budget_abandons_and_still_answers_every_document() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L);

        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());

        assertNull(resident());
    }

    @Test
    public void test_a_scan_that_disagrees_with_the_pk_index_does_not_admit() throws Exception {
        locked(() -> {
            cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL)
                    .add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "zzz", 0, 1, 0));
            return null;
        });

        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());

        assertNull(resident());
    }

    @Test
    public void test_an_admitted_scan_serves_the_next_one_from_memory() throws Exception {
        locked(() -> scanned(Function.identity()));
        final var admitted = cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL);

        final var again = locked(() -> {
            try (var stream = cache.streamCollection(TestGlobals.DB, TestGlobals.COLL)) {
                return stream.toList();
            }
        });

        assertEquals(IDS.size(), again.size());
        for (final var entry : again) {
            assertSame(admitted.get(entry.get_id()), entry);
        }
    }

    private void refuseAtTinyBudget() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", 64L);
        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());
        assertNull(resident());
    }

    private MemoryManagement spiedMemory() throws Exception {
        final var spied = spy(originalMemoryManagement);
        TestUtils.setPrivateField(cache, "memoryManagement", spied);
        return spied;
    }

    private long admittedBytes() throws Exception {
        final var before = cache.userCache().footprintBytes();
        locked(() -> scanned(Function.identity()));
        assertNotNull(resident());
        final var bytes = cache.userCache().footprintBytes() - before;
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
        return bytes;
    }

    @Test
    public void test_a_remembered_refusal_hands_out_no_completion() throws Exception {
        refuseAtTinyBudget();
        final var spied = spiedMemory();

        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());

        verify(spied, never()).admissionCheck(anyLong());
        assertNull(resident());
    }

    @Test
    public void test_a_drop_forgets_the_refusal() throws Exception {
        refuseAtTinyBudget();
        DroppedCollections.forgetCollection(TestGlobals.DB, TestGlobals.COLL);
        var spied = spiedMemory();
        locked(() -> scanned(Function.identity()));
        verify(spied, atLeastOnce()).admissionCheck(anyLong());

        TestUtils.setPrivateField(cache, "memoryManagement", originalMemoryManagement);
        DroppedCollections.forgetDatabase(TestGlobals.DB);
        spied = spiedMemory();
        locked(() -> scanned(Function.identity()));
        verify(spied, atLeastOnce()).admissionCheck(anyLong());
    }

    @Test
    public void test_an_eviction_freeing_enough_retries() throws Exception {
        TestUtils.createTestJoinCollection();
        for (var i = 0; i < 4 * IDS.size(); i++) {
            save(TestGlobals.JOIN_COLL, "o" + i, "other-" + "z".repeat(40) + i);
        }
        locks.lockRead(TestGlobals.DB, TestGlobals.JOIN_COLL);
        try {
            try (var stream = cache.streamCollection(TestGlobals.DB, TestGlobals.JOIN_COLL)) {
                assertEquals(4L * IDS.size(), stream.count());
            }
            assertNotNull(cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.JOIN_COLL));
            final var collectionBytes = admittedBytes();
            TestUtils.setPrivateField(config, "maxMemoryBytes",
                    cache.userCache().footprintBytes() + collectionBytes / 2);

            locked(() -> scanned(Function.identity()));
            assertNull(resident());
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.JOIN_COLL);
        }

        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.JOIN_COLL);
        locked(() -> scanned(Function.identity()));

        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_collection_that_fits_still_admits_once_the_budget_grows() throws Exception {
        refuseAtTinyBudget();
        TestUtils.setPrivateField(config, "maxMemoryBytes", originalMaxMemory);

        locked(() -> scanned(Function.identity()));

        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_collection_that_shrinks_to_fit_after_deletes_admits() throws Exception {
        final var big = new ArrayList<String>();
        for (var i = 0; i < 4 * IDS.size(); i++) {
            big.add("big" + i);
            save(TestGlobals.COLL, "big" + i, "y".repeat(400));
        }
        final var bigBytes = admittedBytes();
        TestUtils.setPrivateField(config, "maxMemoryBytes", cache.userCache().footprintBytes() + bigBytes / 2);
        locked(() -> scanned(Function.identity()));
        assertNull(resident());

        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL, 5_000L));
        try {
            for (final var id : big) {
                final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
                request.set_id(id);
                DeleteOperationHelper.executeDelete(request);
            }
        } finally {
            locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        }
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(IDS.size(), locked(() -> scanned(Function.identity())).size());
        assertEquals(IDS.size(), resident());
    }

    @Test
    public void test_a_collection_read_while_refused_and_vacuumed_answers_its_latest_documents() throws Exception {
        refuseAtTinyBudget();
        for (var round = 0; round < 6; round++) {
            for (final var id : IDS) {
                save(TestGlobals.COLL, id, "round" + round + "-" + id);
            }
        }
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
        final var spied = spiedMemory();

        final var payloads = locked(() -> {
            try (var stream = cache.streamCollection(TestGlobals.DB, TestGlobals.COLL)) {
                return stream.map(entry -> entry.getData().get("payload").asJsonString().getValue()).sorted().toList();
            }
        });

        assertEquals(IDS.stream().sorted().map(id -> "round5-" + id).toList(), payloads);
        verify(spied, never()).admissionCheck(anyLong());
        assertNull(resident());
    }
}
