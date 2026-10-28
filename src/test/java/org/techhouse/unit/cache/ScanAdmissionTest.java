package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AggregateLimitException;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScanAdmissionTest {
    private static final List<String> IDS = List.of("c", "a", "e", "b", "d");
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Configuration config = Configuration.getInstance();
    private long originalMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalMaxMemory = config.getMaxMemoryBytes();
        for (final var id : IDS) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, id);
            object.addProperty("payload", "value-" + id);
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(object);
            request.set_id(id);
            assertNotNull(SaveOperationHelper.executeSave(request));
        }
        cache.userCache().evictCollectionDocuments(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxMemoryBytes", originalMaxMemory);
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
}
