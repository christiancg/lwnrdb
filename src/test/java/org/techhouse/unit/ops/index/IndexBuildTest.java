package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.index.IndexBuild;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexBuildTest {
    private static final List<String> FIELDS = List.of("f1", "f2", "f3", "f4");
    private static final JsonBaseElement[] EDGE_VALUES = {new JsonNumber(0.0), new JsonNumber(-0.0), new JsonNumber(1),
            new JsonNumber(1.0), new JsonString("\ud800"), new JsonString("x\u001fy"), new JsonString("Abc"),
            new JsonString("abc"), new JsonString("#geo(1,2)"), new JsonString("#geo(1.0,2.0)"),
            new JsonString("#datetime(2024-01-01T10:00)"), new JsonObject(), objectK1(), objectK1(), arrayA(), arrayA(),
            new JsonArray()};
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final List<DbEntry> saved = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(IndexBuild.class, "fs", realFs);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject objectK1() {
        final var object = new JsonObject();
        object.addProperty("k", 1);
        return object;
    }

    private static JsonArray arrayA() {
        final var array = new JsonArray();
        array.add(new JsonString("a"));
        return array;
    }

    private void save(String id, JsonBaseElement raw) {
        final var value = raw instanceof JsonString string && JsonCustom.isJsonCustom(string)
                ? CustomTypeFactory.getCustomTypeInstance(string)
                : raw;
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        for (final var field : FIELDS) {
            object.add(field, value);
        }
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        assertEquals(OperationStatus.OK, processor.processMessage(request).getStatus());
    }

    private void seed(long seed, int documents) {
        final var random = new Random(seed);
        for (var i = 0; i < EDGE_VALUES.length; i++) {
            save("e" + i, EDGE_VALUES[i]);
        }
        for (var i = 0; i < documents; i++) {
            save("r" + i, FieldIndexBatchSupport.randomValue(random));
        }
        try (var stored = realFs.streamEntries(TestGlobals.DB, TestGlobals.COLL)) {
            stored.forEach(saved::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void build(List<String> fields) throws Exception {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            IndexBuild.buildAll(TestGlobals.DB, TestGlobals.COLL, fields);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    private FileSystem spyTheBuildFileSystem() throws Exception {
        final var spied = spy(realFs);
        TestUtils.setPrivateStaticField(IndexBuild.class, "fs", spied);
        return spied;
    }

    private boolean anyTypeFileOf(String field) {
        for (final var label : FieldIndexBatchSupport.typeLabels()) {
            if (FieldIndexBatchSupport.typeExists(field, label)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void test_a_build_equals_the_legacy_grouping_build() throws Exception {
        seed(20261025L, 300);

        build(List.of("f1"));

        assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(saved, "f1"), FieldIndexBatchSupport.snapshot("f1"));
        assertTrue(
                FieldIndexBatchSupport.snapshot("f1").get("Number").stream().anyMatch(l -> l.startsWith("0 => e0,e1,")),
                "-0.0 and 0.0 share one line: " + FieldIndexBatchSupport.snapshot("f1").get("Number"));
    }

    @Test
    public void test_one_pass_streams_the_pages_once_for_four_fields_and_admits_nothing() throws Exception {
        seed(7L, 120);
        cache.userCache().evictCollection(TestGlobals.DB, TestGlobals.COLL);
        final var spied = spyTheBuildFileSystem();

        build(FIELDS);

        verify(spied, times(1)).streamPages(TestGlobals.DB, TestGlobals.COLL);
        for (final var field : FIELDS) {
            assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(saved, field),
                    FieldIndexBatchSupport.snapshot(field), field);
            for (final var type : IndexKind.scalarIndexClasses()) {
                assertNull(cache.residentFieldIndex(TestGlobals.DB, TestGlobals.COLL,
                        Cache.getIndexIdentifier(field, type)));
            }
        }
        assertNull(cache.userCache().getCachedCollection(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_complete_cached_collection_is_read_instead_of_the_pages() throws Exception {
        seed(9L, 40);
        final var spied = spyTheBuildFileSystem();

        build(List.of("f2", "f3"));

        verify(spied, never()).streamPages(anyString(), anyString());
        assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(saved, "f3"), FieldIndexBatchSupport.snapshot("f3"));
    }

    @Test
    public void test_a_failure_drops_every_target_field_and_keeps_the_markers() throws Exception {
        seed(11L, 30);
        build(FIELDS);
        for (final var field : FIELDS) {
            realFs.indexMarkers().mark(TestGlobals.DB, TestGlobals.COLL, field);
        }
        final var spied = spyTheBuildFileSystem();
        doThrow(new RuntimeException("disk full")).when(spied).writeIndexFile(anyString(), anyString(), eq("f3"),
                any());

        assertThrows(RuntimeException.class, () -> build(FIELDS));

        for (final var field : FIELDS) {
            assertFalse(anyTypeFileOf(field), field + " keeps no file after a failed build");
            assertTrue(realFs.indexMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, field));
        }
    }

    @Test
    public void test_fields_beyond_the_builder_budget_run_in_further_passes() throws Exception {
        final var configuration = Configuration.getInstance();
        final long original = configuration.getMaxMemoryBytes();
        try {
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", 128L * 1024 * 1024);
            assertEquals(List.of(List.of("f1"), List.of("f2"), List.of("f3"), List.of("f4")),
                    IndexBuild.passesOf(FIELDS, 400_000));
            assertEquals(List.of(List.of("f1", "f2", "f3"), List.of("f4")), IndexBuild.passesOf(FIELDS, 100_000));
            assertEquals(List.of(FIELDS), IndexBuild.passesOf(FIELDS, 0));
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L * 1024 * 1024);
            assertEquals(List.of(List.of("f1", "f2", "f3"), List.of("f4")), IndexBuild.passesOf(FIELDS, 1_000_000));
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_UNLIMITED);
            assertEquals(256L * 1024 * 1024, IndexBuild.passBudgetBytes());
        } finally {
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_several_passes_each_stream_the_pages_once() throws Exception {
        seed(13L, 20);
        cache.userCache().evictCollection(TestGlobals.DB, TestGlobals.COLL);
        final var spied = spyTheBuildFileSystem();
        try {
            TestUtils.setPrivateStaticField(IndexBuild.class, "bytesPerDocumentPerField",
                    IndexBuild.UNBOUNDED_PASS_BUDGET_BYTES);
            build(FIELDS);
        } finally {
            TestUtils.setPrivateStaticField(IndexBuild.class, "bytesPerDocumentPerField",
                    IndexBuild.BUILDER_BYTES_PER_DOCUMENT_PER_FIELD);
        }

        verify(spied, times(4)).streamPages(TestGlobals.DB, TestGlobals.COLL);
        for (final var field : FIELDS) {
            assertEquals(FieldIndexBatchSupport.legacyBuildSnapshot(saved, field),
                    FieldIndexBatchSupport.snapshot(field), field);
        }
    }

    @Test
    public void test_the_fault_point_fires_after_each_field_lands() throws Exception {
        seed(17L, 10);
        final var faultInjector = IocContainer.get(FaultInjector.class);
        final var originalHalt = TestUtils.getPrivateField(faultInjector, "halt", IntConsumer.class);
        final var observed = new ArrayList<Boolean>();
        TestUtils.setPrivateField(faultInjector, "enabled", true);
        TestUtils.setPrivateField(faultInjector, "halt",
                (IntConsumer) _ -> observed.add(anyTypeFileOf("f1") && !anyTypeFileOf("f2")));
        try {
            faultInjector.arm(FaultPoint.INDEX_BUILD_FIELD_WRITTEN, FaultAction.HALT,
                    Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
            build(List.of("f1", "f2"));
        } finally {
            faultInjector.releaseAll();
            TestUtils.setPrivateField(faultInjector, "enabled", false);
            TestUtils.setPrivateField(faultInjector, "halt", originalHalt);
        }

        assertEquals(List.of(true), observed, "the point sits after the first field's files and before the second's");
        assertTrue(anyTypeFileOf("f2"));
    }

    @Test
    public void test_no_fields_reads_nothing() throws Exception {
        final var spied = spyTheBuildFileSystem();

        build(List.of());

        verify(spied, never()).streamPages(anyString(), anyString());
    }

    @Test
    public void test_an_unreadable_page_fails_the_build_with_its_io_error() throws Exception {
        seed(19L, 5);
        cache.userCache().evictCollection(TestGlobals.DB, TestGlobals.COLL);
        final var spied = spyTheBuildFileSystem();
        doThrow(new IOException("unreadable")).when(spied).streamPages(TestGlobals.DB, TestGlobals.COLL);

        assertThrows(IOException.class, () -> build(List.of("f1")));
        assertFalse(anyTypeFileOf("f1"));
    }
}
