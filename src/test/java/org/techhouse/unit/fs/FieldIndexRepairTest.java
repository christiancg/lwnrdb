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
}
