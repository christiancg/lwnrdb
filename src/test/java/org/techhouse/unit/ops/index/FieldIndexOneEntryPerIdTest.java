package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.index.FieldIndexBatch;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexOneEntryPerIdTest {
    private static final String FIELD = "tag";
    private static final String MOVING_ID = "x";
    private static final String SLOT_CLASS = "org.techhouse.ops.index.FieldIndexSlot";
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final PendingIndexWrites pending = IocContainer.get(PendingIndexWrites.class);
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

    private static String duplicateIn(Collection<? extends List<? extends FieldIndexEntry<?>>> lists) {
        final var seen = new HashMap<String, String>();
        for (final var list : lists) {
            for (final var entry : list) {
                final var where = FieldIndexEntry.fileKeyOf(entry.getValue());
                for (final var id : entry.getIds()) {
                    final var before = seen.put(id, where);
                    if (before != null) {
                        return id + " sits under both " + before + " and " + where;
                    }
                }
            }
        }
        return null;
    }

    private String duplicateOnDisk() throws IOException {
        return duplicateIn(FieldIndexBatchSupport.loadedTypes(FIELD).values());
    }

    private String duplicateResident() {
        final var lists = new ArrayList<List<? extends FieldIndexEntry<?>>>();
        for (final var type : IndexKind.scalarIndexClasses()) {
            final var resident = support.resident(FIELD, type);
            if (resident != null) {
                lists.add(resident);
            }
        }
        for (final var kind : IndexKind.HASH_KINDS) {
            final var resident = support.resident(FIELD, kind);
            if (resident != null) {
                lists.add(resident);
            }
        }
        return duplicateIn(lists);
    }

    private void assertOneEntryPerId(String when) throws IOException {
        assertNull(duplicateOnDisk(), when + ", in a fresh load of the .idx plus its .idxlog");
        assertNull(duplicateResident(), when + ", in the resident lists");
    }

    private void applyToMovingIdInOneBatch(JsonBaseElement... values) throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lockIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        try {
            final var batch = FieldIndexBatch.open(TestGlobals.DB, TestGlobals.COLL, FIELD, List.of(MOVING_ID));
            for (final var value : values) {
                batch.apply(MOVING_ID, value);
            }
            batch.flush();
        } finally {
            locks.releaseIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        }
    }

    @Test
    public void test_a_value_change_moves_the_id_out_of_its_old_entry() throws Exception {
        support.put("a", new JsonNumber(5));
        support.put("b", new JsonNumber(5));
        support.applyBatch(FIELD, List.of("a", "b"));
        support.admitEveryType(FIELD);
        support.put("a", new JsonNumber(7));

        support.applyBatch(FIELD, List.of("a"));

        assertOneEntryPerId("after a value change");
        assertEquals(Set.of("5 => b", "7 => a"), FieldIndexBatchSupport.snapshot(FIELD).get("Number"));
    }

    @Test
    public void test_a_value_moving_across_every_type_and_kind_stays_in_one_entry() throws Exception {
        final var object = new JsonObject();
        object.add("k", new JsonNumber(1));
        final var array = new JsonArray();
        array.add(new JsonString("a"));
        final JsonBaseElement[] values = {new JsonNumber(5), new JsonString("five"), JsonBoolean.of(true),
                new JsonString("#geo(1,2)"), object, array, new JsonNumber(6), new JsonString("Five")};
        support.put("other", new JsonNumber(5));
        support.applyBatch(FIELD, List.of("other"));
        for (var i = 0; i < values.length; i++) {
            if (i % 2 == 0) {
                support.admitEveryType(FIELD);
            } else {
                cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
            }
            support.put("x", values[i]);

            support.applyBatch(FIELD, List.of("x"));

            assertOneEntryPerId("after moving to " + values[i]);
        }
    }

    @Test
    public void test_one_id_changed_several_times_in_one_batch_ends_in_one_entry() throws Exception {
        support.put("seed", new JsonNumber(1));
        support.applyBatch(FIELD, List.of("seed"));
        support.admitEveryType(FIELD);

        applyToMovingIdInOneBatch(new JsonNumber(1), new JsonNumber(2), new JsonString("s"), new JsonNumber(1));

        assertOneEntryPerId("after one batch moved the id four times");
        assertEquals(Set.of("1 => seed,x"), FieldIndexBatchSupport.snapshot(FIELD).get("Number"));
    }

    @Test
    public void test_saves_sharing_a_new_value_share_one_entry() throws Exception {
        support.put("a", new JsonNumber(1));
        support.applyBatch(FIELD, List.of("a"));
        support.admitEveryType(FIELD);
        support.put("a", new JsonNumber(9));
        support.put("b", new JsonNumber(9));
        support.applyBatch(FIELD, List.of("a", "b"));
        support.put("c", new JsonNumber(9));

        support.applyBatch(FIELD, List.of("c"));

        assertOneEntryPerId("after three saves of one new value");
        assertEquals(Set.of("9 => a,b,c"), FieldIndexBatchSupport.snapshot(FIELD).get("Number"));
    }

    @Test
    public void test_a_batch_emptying_an_entry_drops_it() throws Exception {
        support.put("a", new JsonNumber(3));
        support.put("b", new JsonNumber(8));
        support.applyBatch(FIELD, List.of("a", "b"));
        support.admitEveryType(FIELD);
        support.put("a", new JsonNumber(4));
        support.delete("b");

        support.applyBatch(FIELD, List.of("a", "b"));

        assertOneEntryPerId("after a batch emptied two entries");
        assertEquals(Set.of("4 => a"), FieldIndexBatchSupport.snapshot(FIELD).get("Number"));
        for (final var entry : support.resident(FIELD, Number.class)) {
            assertFalse(entry.getIds().isEmpty(), "an emptied entry must not stay resident");
        }
    }

    private DbEntry document(String id, JsonBaseElement value) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.add(FIELD, value);
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

    @Test
    public void test_a_reload_of_the_base_file_plus_its_log_keeps_one_entry_per_id() throws Exception {
        for (var i = 0; i < 20; i++) {
            document("r" + i, new JsonNumber(i % 4));
        }
        registerIndex();
        for (var i = 0; i < 20; i += 3) {
            support.put("r" + i, i % 2 == 0 ? new JsonNumber(i % 4 + 1) : new JsonString("s" + i));
        }
        support.applyBatch(FIELD, List.of("r0", "r3", "r6", "r9", "r12", "r15", "r18"));
        assertTrue(FieldIndexBatchSupport.indexFile(FIELD, "Number").exists());
        assertTrue(FieldIndexBatchSupport.logFile(FIELD, "Number").exists());
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);

        support.admitEveryType(FIELD);

        assertOneEntryPerId("after replaying the log over the base");
        var total = 0;
        for (final var entries : FieldIndexBatchSupport.loadedTypes(FIELD).values()) {
            for (final var entry : entries) {
                total += entry.getIds().size();
            }
        }
        assertEquals(20, total, "the sum of entry sizes is the distinct id count");
    }

    @Test
    public void test_a_failed_batch_leaves_a_second_entry_only_for_an_id_it_keeps_pending() throws Exception {
        final var doc = document("f1", new JsonString("before"));
        document("f2", new JsonNumber(1));
        registerIndex();
        support.admitEveryType(FIELD);
        doc.getData().add(FIELD, new JsonNumber(5));
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", spied);
        doThrow(new IOException("disk full")).when(spied).appendIndexLog(anyString(), anyString(), anyString(),
                eq("String"), any(), any());
        final var generation = pending.mark(TestGlobals.DB, TestGlobals.COLL, "f1");
        try {
            assertThrows(IOException.class,
                    () -> IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("f1")));
            assertNotNull(duplicateOnDisk(), "the Number log landed before the String one failed");
            assertNull(duplicateResident(), "the failed batch evicted every type");

            final var entries = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD,
                    ReadObserver.NONE);

            assertNotNull(entries, "the reconciled entries cover both documents");
            assertNull(duplicateIn(List.of(entries)), "reconciling the pending id leaves it in one entry");
            assertEquals(2, entries.stream().mapToInt(entry -> entry.getIds().size()).sum());
            assertNull(IndexHelper.getSortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD, (_, _) -> 0, 10,
                    ReadObserver.NONE), "the sorted-ids path declines while an id is pending");

            TestUtils.setPrivateStaticField(Class.forName(SLOT_CLASS), "fs", realFs);
            IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of("f1"));

            assertOneEntryPerId("after the retried batch");
        } finally {
            pending.clear(TestGlobals.DB, TestGlobals.COLL, "f1", generation);
        }
    }

    @Test
    public void test_random_batches_keep_one_entry_per_id() throws Exception {
        final var random = new Random(2026);
        for (var batch = 0; batch < 300; batch++) {
            if (batch % 20 == 0) {
                support.admitEveryType(FIELD);
            } else if (batch % 37 == 0) {
                cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
            }
            final var size = batch % 10 == 0 ? 150 : 1 + random.nextInt(12);
            final var ids = FieldIndexBatchSupport.randomIds(random, size, 200);
            for (final var id : ids) {
                support.mutate(id, random);
            }

            support.applyBatch(FIELD, ids);

            assertOneEntryPerId("after batch " + batch);
        }
    }
}
