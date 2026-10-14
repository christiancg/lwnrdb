package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexBatchTest {
    private static final String FIELD = "tag";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        support = new FieldIndexBatchSupport();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private FileSystem spyTheBatchFileSystem() throws Exception {
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        return spied;
    }

    private DbEntry document(String id, String value) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty(FIELD, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        return entry;
    }

    private void registerIndex() throws Exception {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    private Map<String, Set<String>> stringIdsOnDisk() throws IOException {
        final var byValue = new java.util.HashMap<String, Set<String>>();
        final var loaded = realFs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        if (loaded != null) {
            for (final var entry : loaded) {
                byValue.put(entry.getValue(), new HashSet<>(entry.getIds()));
            }
        }
        return byValue;
    }

    private boolean anyTypeResident() {
        for (final var type : IndexKind.scalarIndexClasses()) {
            if (support.resident(FIELD, type) != null) {
                return true;
            }
        }
        return IndexKind.HASH_KINDS.stream().anyMatch(kind -> support.resident(FIELD, kind) != null);
    }

    private List<String> idsSettingTheirValues(Random random, int count) {
        final var ids = FieldIndexBatchSupport.randomIds(random, count > 0 ? count : 1 + random.nextInt(12), 40);
        for (final var id : ids) {
            support.mutate(id, random);
        }
        return ids;
    }

    @Test
    public void eachDirtyTypeIsRewrittenOncePerBatch() throws Exception {
        final var spied = spyTheBatchFileSystem();
        final var random = new Random(7);

        support.applyBatch(FIELD, idsSettingTheirValues(random, 200));

        for (final var label : FieldIndexBatchSupport.typeLabels()) {
            verify(spied, atMost(1)).rewriteIndexType(anyString(), anyString(), eq(FIELD), eq(label), any());
        }
        verify(spied, times(1)).rewriteIndexType(anyString(), anyString(), eq(FIELD), eq("String"), any());
        verify(spied, times(1)).rewriteIndexType(anyString(), anyString(), eq(FIELD), eq("Number"), any());
    }

    @Test
    public void untouchedTypesAreNotRewritten() throws Exception {
        support.put("n1", new JsonString("seed"));
        support.applyBatch(FIELD, List.of("n1"));
        final var spied = spyTheBatchFileSystem();

        support.applyBatch(FIELD, List.of("n1", "n1"));

        verify(spied, never()).rewriteIndexType(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void emptiedTypeFileIsDeleted() throws Exception {
        support.put("b1", JsonBoolean.of(true));
        support.put("b2", JsonBoolean.of(false));
        support.applyBatch(FIELD, List.of("b1", "b2"));
        assertTrue(FieldIndexBatchSupport.indexFile(FIELD, "Boolean").exists());
        support.admitEveryType(FIELD);
        assertNotNull(support.resident(FIELD, Boolean.class));
        support.put("b1", new JsonString("x"));
        support.delete("b2");

        support.applyBatch(FIELD, List.of("b1", "b2"));

        assertFalse(FieldIndexBatchSupport.indexFile(FIELD, "Boolean").exists(),
                "a type the batch emptied must be deleted, never left as an empty file");
        assertNull(support.resident(FIELD, Boolean.class), "an emptied resident list must not answer as an index");
        assertEquals(Map.of("x", Set.of("b1")), stringIdsOnDisk());
    }

    @Test
    public void residentListStaysSortedAndEqualToAFreshLoad() throws Exception {
        final var random = new Random(500);
        for (var batch = 0; batch < 500; batch++) {
            if (batch % 25 == 0) {
                support.admitEveryType(FIELD);
            }
            support.applyBatch(FIELD, idsSettingTheirValues(random, 0));
            assertNull(support.residentMismatch(FIELD), "after batch " + batch);
        }
        assertTrue(anyTypeResident(), "the batches must have run against resident lists");
    }

    @Test
    public void failureEvictsTheResidentList() throws Exception {
        final var doc = document("f1", "before");
        registerIndex();
        support.admitEveryType(FIELD);
        assertNotNull(support.resident(FIELD, String.class));
        doc.getData().addProperty(FIELD, "after");
        final var spied = spyTheBatchFileSystem();
        doThrow(new IOException("disk full")).when(spied).rewriteIndexType(anyString(), anyString(), anyString(),
                anyString(), any());

        assertThrows(IOException.class,
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("f1")));

        assertFalse(anyTypeResident(), "a failed batch mutated the resident list before its write failed");
        assertEquals(Map.of("before", Set.of("f1")), stringIdsOnDisk());
    }

    @Test
    public void successKeepsTheResidentList() throws Exception {
        final var doc = document("k1", "before");
        registerIndex();
        support.admitEveryType(FIELD);
        final var resident = support.resident(FIELD, String.class);
        doc.getData().addProperty(FIELD, "after");

        IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("k1"));

        assertEquals(resident, support.resident(FIELD, String.class));
        assertEquals(List.of("after"), resident.stream().map(FieldIndexEntry::getValue).toList());
        assertEquals(Map.of("after", Set.of("k1")), stringIdsOnDisk());
    }

    @Test
    public void aSuccessfulBatchCachesTheIndexItWrote() throws Exception {
        final var doc = document("w1", "before");
        registerIndex();
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        doc.getData().addProperty(FIELD, "after");

        IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("w1"));

        final var cached = support.resident(FIELD, String.class);
        assertNotNull(cached, "the writer publishes the list it just wrote so the next batch skips the disk read");
        assertEquals(List.of("after"), cached.stream().map(FieldIndexEntry::getValue).toList());
        assertEquals(Map.of("after", Set.of("w1")), stringIdsOnDisk());
    }

    @Test
    public void aFailedBatchCachesNothing() throws Exception {
        final var doc = document("w2", "before");
        registerIndex();
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        doc.getData().addProperty(FIELD, "after");
        final var spied = spyTheBatchFileSystem();
        doThrow(new IOException("disk full")).when(spied).rewriteIndexType(anyString(), anyString(), anyString(),
                anyString(), any());

        assertThrows(IOException.class,
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("w2")));

        assertFalse(anyTypeResident(), "a list whose write failed differs from the file and must not be cached");
        assertEquals(Map.of("before", Set.of("w2")), stringIdsOnDisk());
    }

    @Test
    public void anUnreadableTypeFileFailsTheBatchAndIsLeftUntouched() throws Exception {
        final var doc = document("u1", "before");
        registerIndex();
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var stringFile = FieldIndexBatchSupport.indexFile(FIELD, "String").toPath();
        final var unreadable = "no index line here\nnor here\n".getBytes(StandardCharsets.UTF_8);
        Files.write(stringFile, unreadable);
        doc.getData().addProperty(FIELD, "after");

        assertThrows(IOException.class,
                () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("u1")));

        assertArrayEquals(unreadable, Files.readAllBytes(stringFile),
                "a file in which no line parsed is reported, never rewritten from an empty base");
        assertFalse(anyTypeResident());
    }

    @Test
    public void droppedIndexMidBatchWritesNothing() throws Exception {
        document("d1", "value");
        registerIndex();
        IndexHelper.dropIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        AdminOperationHelper.deleteIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);

        IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("d1"));

        for (final var label : FieldIndexBatchSupport.typeLabels()) {
            assertFalse(FieldIndexBatchSupport.indexFile(FIELD, label).exists(), label);
        }
    }

    @Test
    public void aFileWithMixedLineEndsKeepsEveryValue() throws Exception {
        final var separator = Globals.ID_SEPARATOR;
        Files.writeString(FieldIndexBatchSupport.indexFile(FIELD, "String").toPath(),
                "alpha" + separator + "a\r\nbeta" + separator + "b\ngamma" + separator + "c", StandardCharsets.UTF_8);
        support.put("b2", new JsonString("beta"));

        support.applyBatch(FIELD, List.of("b2"));

        assertEquals(Map.of("alpha", Set.of("a"), "beta", Set.of("b", "b2"), "gamma", Set.of("c")), stringIdsOnDisk());
    }

    @Test
    public void theFaultPointFiresAfterTheFirstTypeFileAndBeforeTheNext() throws Exception {
        final var numberFile = FieldIndexBatchSupport.indexFile(FIELD, "Number");
        final var stringFile = FieldIndexBatchSupport.indexFile(FIELD, "String");
        support.put("n", new JsonNumber(5));
        support.put("s", new JsonString("five"));
        final var faultInjector = IocContainer.get(FaultInjector.class);
        final var originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        final var observed = new ArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> observed.add(numberFile.exists() && !stringFile.exists()));
        try {
            faultInjector.arm(FaultPoint.INDEX_BATCH_FLUSHED, FaultAction.HALT,
                    Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));

            support.applyBatch(FIELD, List.of("n", "s"));
        } finally {
            faultInjector.releaseAll();
            TestUtils.setPrivateField(faultInjector, "enabled", false);
            TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        }

        assertEquals(List.of(true), observed, "the point sits after the first type file and before the second");
        assertTrue(stringFile.exists());
    }
}
