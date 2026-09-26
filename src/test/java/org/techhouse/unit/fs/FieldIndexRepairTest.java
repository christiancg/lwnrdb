package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexRepairTest {
    private static final String FIELD = "score";

    private FileSystem fs;

    @BeforeEach
    public void setUp() throws Exception {
        final var config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        fs = new FileSystem();
        TestUtils.setDbPath(fs, TestGlobals.PATH);
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        fs.createDatabaseFolder(TestGlobals.DB);
        fs.createCollectionFile(TestGlobals.DB, TestGlobals.COLL);
    }

    @AfterEach
    public void tearDown() {
        final var dbDir = new File(TestGlobals.PATH);
        if (dbDir.exists() && dbDir.isDirectory() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private File indexFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + FIELD + Globals.INDEX_FILE_NAME_SEPARATOR + Globals.INDEX_TYPE_NUMBER + Globals.INDEX_FILE_EXTENSION);
    }

    private void writeIndexWithATornLine() throws Exception {
        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, FIELD,
                java.util.Map.of(Number.class,
                        List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 1.0, Set.of("a")),
                                new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, 2.0, Set.of("b")))));
        final var file = indexFile();
        Files.writeString(file.toPath(),
                Files.readString(file.toPath(), StandardCharsets.UTF_8) + "not-an-entry" + Globals.NEWLINE,
                StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_torn_line_is_healed_and_the_survivors_are_kept() throws Exception {
        writeIndexWithATornLine();

        final var entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);

        assertEquals(2, entries.size());
        final var healed = Files.readString(indexFile().toPath(), StandardCharsets.UTF_8);
        assertFalse(healed.contains("not-an-entry"), "the malformed line must be dropped from the file");
    }

    @Test
    public void test_two_concurrent_repairs_do_not_corrupt_the_file() throws Exception {
        writeIndexWithATornLine();
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(2);
        final var failure = new AtomicReference<Throwable>();

        for (var i = 0; i < 2; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    final var entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD,
                            Number.class);
                    assertEquals(2, entries.size());
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertNull(failure.get(),
                "the repair writes through a temp path derived from the target, so two unlocked repairs"
                        + " truncated each other's file and one read failed outright");
        final var entries = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);
        assertEquals(2, entries.size(), "neither repair may lose a surviving entry");
    }

    @Test
    public void test_a_file_where_no_line_parses_is_not_rewritten() throws Exception {
        writeIndexWithATornLine();
        final var file = indexFile();
        java.nio.file.Files.writeString(file.toPath(),
                "a|0|20|0|0" + Globals.NEWLINE + "b|20|20|0|0" + Globals.NEWLINE);
        final var before = java.nio.file.Files.readAllBytes(file.toPath());

        final var read = fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);

        assertNull(read, "a file this loader cannot read at all must be declined, not answered from");
        assertArrayEquals(before, java.nio.file.Files.readAllBytes(file.toPath()),
                "a total parse failure means the file is not what the loader expects, so it must not be rewritten");
    }

    @Test
    public void test_a_field_index_named_id_is_refused_and_leaves_the_pk_index_alone() throws Exception {
        final var data = new org.techhouse.ejson.elements.JsonObject();
        data.addProperty("name", "test");
        final var entry = org.techhouse.data.DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("kept");
        fs.insertIntoCollection(entry);
        final var pkFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-pk.idx");
        final var before = java.nio.file.Files.readAllBytes(pkFile.toPath());

        assertNull(fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, Globals.PK_FIELD, String.class),
                "the field-index reader must never resolve the pk index");

        assertArrayEquals(before, java.nio.file.Files.readAllBytes(pkFile.toPath()));
        assertEquals(1, fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).size());
    }

    @Test
    public void test_a_field_index_named_tombstones_is_refused() throws Exception {
        assertNull(fs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, Globals.TOMBSTONE_FILE_NAME,
                String.class));
    }

    @Test
    public void test_a_field_index_write_named_id_is_refused() throws Exception {
        final var data = new org.techhouse.ejson.elements.JsonObject();
        data.addProperty("name", "test");
        final var entry = org.techhouse.data.DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id("kept");
        fs.insertIntoCollection(entry);
        final var pkFile = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-pk.idx");
        final var before = java.nio.file.Files.readAllBytes(pkFile.toPath());

        fs.writeIndexFile(TestGlobals.DB, TestGlobals.COLL, Globals.PK_FIELD, java.util.Map.of(String.class,
                List.of(new FieldIndexEntry<>(TestGlobals.DB, TestGlobals.COLL, "x", Set.of("y")))));

        assertArrayEquals(before, java.nio.file.Files.readAllBytes(pkFile.toPath()));
    }

    @Test
    public void test_every_field_index_name_carries_a_type_segment() {
        final var reserved = Set.of(TestGlobals.COLL + "-pk.idx", TestGlobals.COLL + "-tombstones.idx");
        for (final var type : List.of(Globals.INDEX_TYPE_NUMBER, Globals.INDEX_TYPE_STRING, Globals.INDEX_TYPE_BOOLEAN,
                Globals.INDEX_TYPE_OBJECT, Globals.INDEX_TYPE_ARRAY)) {
            final var name = TestGlobals.COLL + "-" + FIELD + "-" + type + Globals.INDEX_FILE_EXTENSION;
            assertFalse(reserved.contains(name), name + " must not collide with a reserved file");
        }
    }
}
