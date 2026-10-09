package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageCompactorBoundsTest {
    private FileSystem fs;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "filePath", TestGlobals.PATH);
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
        if (dbDir.exists() && Objects.requireNonNull(dbDir.listFiles()).length > 0) {
            TestUtils.deleteFolder(dbDir);
        }
    }

    private static File collectionFolder() {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
    }

    private static File page() {
        return new File(collectionFolder(), TestGlobals.COLL + "-0" + Globals.DB_FILE_EXTENSION);
    }

    private static File pkIndexFile() {
        return new File(collectionFolder(), TestGlobals.COLL + Globals.INDEX_FILE_NAME_SEPARATOR
                + Globals.PK_INDEX_FILE_NAME + Globals.INDEX_FILE_EXTENSION);
    }

    private static DbEntry document(String id, String payload) {
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id(id);
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("payload", payload);
        entry.setData(data);
        entry.setPage(0L);
        return entry;
    }

    private PkIndexEntry danglingEntry() throws IOException {
        final var neighbour = fs.insertIntoCollection(document("w", "neighbour"));
        final var lost = fs.insertIntoCollection(document("x", "lost"));
        try (var file = new RandomAccessFile(page(), Globals.RW_PERMISSIONS)) {
            file.setLength(neighbour.getPosition() + neighbour.getLength());
        }
        return lost;
    }

    private boolean anyCompactionMarker() {
        final var markers = collectionFolder().listFiles((_, name) -> name.endsWith(".compacting"));
        return markers != null && markers.length > 0;
    }

    @Test
    public void test_an_update_of_an_entry_past_its_page_is_refused_before_writing() throws IOException {
        final var lost = danglingEntry();
        final var pageBytes = Files.readAllBytes(page().toPath());
        final var pkBytes = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(IOException.class, () -> fs.updateFromCollection(document("x", "rewritten"), lost));

        assertArrayEquals(pageBytes, Files.readAllBytes(page().toPath()));
        assertArrayEquals(pkBytes, Files.readAllBytes(pkIndexFile().toPath()));
        assertFalse(anyCompactionMarker());
    }

    @Test
    public void test_a_delete_of_an_entry_past_its_page_is_refused_before_writing() throws IOException {
        final var lost = danglingEntry();
        final var pageBytes = Files.readAllBytes(page().toPath());
        final var pkBytes = Files.readAllBytes(pkIndexFile().toPath());

        assertThrows(RuntimeException.class, () -> fs.deleteFromCollection(lost));

        assertArrayEquals(pageBytes, Files.readAllBytes(page().toPath()));
        assertArrayEquals(pkBytes, Files.readAllBytes(pkIndexFile().toPath()));
        assertFalse(anyCompactionMarker());
    }
}
