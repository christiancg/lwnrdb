package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.FindByIdResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class PkIndexPointWriteTest {
    private static final int DOCUMENTS = 1000;
    private static final long SMALL_PAGE = 4096L;
    private static final long SHIPPED_PAGE = 2_097_152L;
    private static final Configuration configuration = Configuration.getInstance();
    private static final Map<String, Integer> SCANS = new ConcurrentHashMap<>();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private String originalMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalMaxMemory = String.valueOf(configuration.getMaxMemoryBytes());
        SCANS.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", SHIPPED_PAGE);
        TestUtils.setPrivateField(configuration, "maxMemory", originalMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String idOf(int i) {
        return String.format("d%04d", i);
    }

    private static JsonObject document(String id, int payload) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        object.add("payload", new JsonString("x".repeat(payload)));
        return object;
    }

    private OperationResponse save(String id, int payload) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, payload));
        request.set_id(id);
        return processor.processMessage(request);
    }

    private OperationResponse delete(String id) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request);
    }

    private OperationResponse find(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request);
    }

    private void seed(int count, int payload) {
        for (var i = 0; i < count; i++) {
            assertEquals(OperationStatus.OK, save(idOf(i), payload).getStatus());
        }
    }

    private ScanCountingPkIndex countingResidentIndex() throws Exception {
        final var mapType = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var resident = TestUtils.getPrivateField(cache.userCache(), "pkIndexMap", mapType);
        final var identifier = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);
        final var counting = new ScanCountingPkIndex(resident.get(identifier));
        resident.put(identifier, counting);
        return counting;
    }

    private List<PkIndexEntry> residentIndex() throws Exception {
        return new ArrayList<>(cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL));
    }

    private static void assertSorted(List<PkIndexEntry> rows) {
        for (var i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).getValue().compareTo(rows.get(i).getValue()) < 0,
                    "rows " + (i - 1) + " and " + i + " are out of order");
        }
    }

    private void assertFound(String id) {
        assertInstanceOf(FindByIdResponse.class, find(id), id + " must still be readable");
    }

    private void assertNotFound(String id) {
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), find(id).getErrorCode(), id + " must be gone");
    }

    @Test
    public void deleteNeverScansOrSortsThePkIndex() throws Exception {
        seed(DOCUMENTS, 20);
        countingResidentIndex();

        assertEquals(OperationStatus.OK, delete(idOf(DOCUMENTS / 2)).getStatus());

        assertEquals(Map.of(), SCANS);
    }

    @Test
    public void deleteKeepsTheIndexSortedAndDropsOnlyThatId() throws Exception {
        seed(DOCUMENTS, 20);
        for (final var victim : List.of(0, DOCUMENTS / 2, DOCUMENTS - 1)) {
            final var before = residentIndex().size();

            assertEquals(OperationStatus.OK, delete(idOf(victim)).getStatus());

            final var after = residentIndex();
            assertEquals(before - 1, after.size());
            assertSorted(after);
            assertNotFound(idOf(victim));
        }
        assertFound(idOf(1));
        assertFound(idOf(DOCUMENTS / 2 - 1));
        assertFound(idOf(DOCUMENTS / 2 + 1));
        assertFound(idOf(DOCUMENTS - 2));
    }

    @Test
    public void deleteOfAMissingIdAnswersNotFoundAndChangesNothing() throws Exception {
        seed(10, 20);
        final var before = residentIndex();

        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), delete("absent").getErrorCode());

        assertEquals(before, residentIndex());
    }

    @Test
    public void deleteComparesTheIdExactly() {
        assertEquals(OperationStatus.OK, save("Ab", 20).getStatus());
        assertEquals(OperationStatus.OK, save("ab", 20).getStatus());

        assertEquals(OperationStatus.OK, delete("Ab").getStatus());

        assertNotFound("Ab");
        assertFound("ab");
    }

    @Test
    public void deletingTheOnlyDocumentLeavesAnEmptyIndexThatAcceptsAnInsert() throws Exception {
        seed(1, 20);

        assertEquals(OperationStatus.OK, delete(idOf(0)).getStatus());
        assertTrue(residentIndex().isEmpty());
        assertEquals(OperationStatus.OK, save(idOf(7), 20).getStatus());

        assertEquals(List.of(idOf(7)), residentIndex().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void saveUpdateReplacesTheRowInPlace() throws Exception {
        seed(DOCUMENTS, 20);
        final var counting = countingResidentIndex();
        final var target = idOf(DOCUMENTS / 3);
        final var slot = Collections.binarySearch(counting, target);
        final var oldRow = counting.get(slot);

        assertEquals(OperationStatus.OK, save(target, 40).getStatus());

        assertEquals(Map.of(), SCANS);
        assertEquals(DOCUMENTS, counting.size());
        final var newRow = counting.get(slot);
        assertEquals(target, newRow.getValue());
        assertNotEquals(oldRow.getLength(), newRow.getLength());
        assertSorted(counting);
    }

    @Test
    public void saveInsertKeepsTheIndexSorted() throws Exception {
        save("m", 20);
        save("c", 20);
        save("z", 20);
        save("a", 20);
        save("n", 20);

        assertEquals(List.of("a", "c", "m", "n", "z"), residentIndex().stream().map(PkIndexEntry::getValue).toList());
    }

    @Test
    public void relocatedUpdateReplacesTheRowInPlace() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", SMALL_PAGE);
        seed(10, 320);
        final var counting = countingResidentIndex();
        final var target = idOf(0);
        final var oldPage = counting.getFirst().getPage();

        assertEquals(OperationStatus.OK, save(target, 1500).getStatus());

        assertEquals(Map.of(), SCANS);
        assertEquals(10, counting.size());
        assertEquals(target, counting.getFirst().getValue());
        assertNotEquals(oldPage, counting.getFirst().getPage(), "the grown document must have moved page");
        assertSorted(counting);
        for (var i = 0; i < 10; i++) {
            assertFound(idOf(i));
        }
    }

    @Test
    public void deleteAfterAVacuumRemovesTheRightRow() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", SMALL_PAGE);
        seed(6, 280);
        for (var round = 1; round <= 40; round++) {
            assertEquals(OperationStatus.OK, save(idOf(round % 6), 280).getStatus());
        }

        assertEquals(OperationStatus.OK, delete(idOf(3)).getStatus());

        assertNotFound(idOf(3));
        for (final var survivor : List.of(0, 1, 2, 4, 5)) {
            assertFound(idOf(survivor));
        }
        assertSorted(residentIndex());
    }

    @Test
    public void deleteWorksOnAPkIndexThatIsNeverCached() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemory", "-1");
        seed(20, 20);

        assertEquals(OperationStatus.OK, delete(idOf(5)).getStatus());

        assertNotFound(idOf(5));
        assertFound(idOf(4));
        assertFound(idOf(6));
    }

    private static final class ScanCountingPkIndex extends ArrayList<PkIndexEntry> {
        ScanCountingPkIndex(Collection<PkIndexEntry> rows) {
            super(rows);
        }

        @Override
        public void sort(Comparator<? super PkIndexEntry> comparator) {
            SCANS.merge("sort", 1, Integer::sum);
            super.sort(comparator);
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Stream<PkIndexEntry> stream() {
            SCANS.merge("stream", 1, Integer::sum);
            return super.stream();
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Iterator<PkIndexEntry> iterator() {
            SCANS.merge("iterator", 1, Integer::sum);
            return super.iterator();
        }

        @Override
        public void forEach(Consumer<? super PkIndexEntry> action) {
            SCANS.merge("forEach", 1, Integer::sum);
            super.forEach(action);
        }

        @Override
        public boolean remove(Object row) {
            SCANS.merge("remove(Object)", 1, Integer::sum);
            return super.remove(row);
        }

        @Override
        public boolean removeIf(Predicate<? super PkIndexEntry> filter) {
            SCANS.merge("removeIf", 1, Integer::sum);
            return super.removeIf(filter);
        }
    }
}
