package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.index.IndexEntryReader;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexCoverageUnknownTest {
    private static final String FIELD = "score";
    private static final Comparator<FieldIndexEntry<?>> ASCENDING = Comparator
            .comparingDouble(entry -> ((Number) entry.getValue()).doubleValue());

    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 20; i++) {
            final var data = new JsonObject();
            data.addProperty("_id", "d" + i);
            data.addProperty(FIELD, i % 5);
            entries.add(DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data));
        }
        IocContainer.get(FileSystem.class).bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, entries);
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        cache.evictCollection(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void forgetTheLiveRowCount() throws Exception {
        Files.setLastModifiedTime(
                Path.of(TestGlobals.PATH, TestGlobals.DB, TestGlobals.COLL, TestGlobals.COLL + "-pk.idx"),
                FileTime.fromMillis(0L));
    }

    @Test
    public void aLockFreeReaderWithAKnownCountUsesTheIndex() throws Exception {
        assertEquals(20, cache.pkCountForCoverage(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(IndexEntryReader.sortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD, ASCENDING, 100,
                ReadObserver.NONE));
        final var distinct = IndexEntryReader.distinctValuesForField(TestGlobals.DB, TestGlobals.COLL, FIELD,
                ReadObserver.NONE);
        assertNotNull(distinct);
        assertEquals(5, distinct.size());
    }

    @Test
    public void anUnknownCountDeclinesTheSortAndTheDistinct() throws Exception {
        forgetTheLiveRowCount();

        assertEquals(-1, cache.pkCountForCoverage(TestGlobals.DB, TestGlobals.COLL));
        assertNull(IndexEntryReader.sortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD, ASCENDING, 100,
                ReadObserver.NONE), "a count that is not known never lets an index answer");
        assertNull(IndexEntryReader.distinctValuesForField(TestGlobals.DB, TestGlobals.COLL, FIELD, ReadObserver.NONE));
        assertNull(
                IndexEntryReader.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD, ReadObserver.NONE));
    }
}
