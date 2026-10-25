package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestGlobals;

public class FileSystemEncodedWriteTest extends FileSystemTestBase {
    private static final String UNICODE = "é😀" + (char) 0x2028 + "\"q\"";

    private final Map<Long, List<String>> expectedPages = new LinkedHashMap<>();

    private static DbEntry document(String id, String value, long page) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", value);
        data.addProperty("unicode", UNICODE);
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id(id);
        entry.setData(data);
        entry.setPage(page);
        entry.setVersion(1_700_000_000_000L);
        return entry;
    }

    private static String legacyLine(String id, String value) {
        return document(id, value, 0L).toFileEntry() + Globals.NEWLINE;
    }

    private void expectAppended(long page, String id, String value) {
        expectedPages.computeIfAbsent(page, _ -> new ArrayList<>()).add(legacyLine(id, value));
    }

    private void expectRemovedFromFirstPage(String id) {
        expectedPages.get(0L).replaceAll(line -> line.contains("\"_id\":\"" + id + "\"") ? hole(line) : line);
    }

    private static String hole(String line) {
        return " ".repeat(line.getBytes(StandardCharsets.UTF_8).length - Globals.NEWLINE.length()) + Globals.NEWLINE;
    }

    private static File page(long page) {
        return new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL
                        + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + page + ".dat");
    }

    private void assertPagesAsLegacy() throws Exception {
        for (final var expected : expectedPages.entrySet()) {
            assertEquals(String.join("", expected.getValue()),
                    Files.readString(page(expected.getKey()).toPath(), StandardCharsets.UTF_8));
        }
        for (final var row : fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL)) {
            assertEquals(row.getValue(), fs.getById(row).get_id());
        }
    }

    private List<PkIndexEntry> resident() throws IOException {
        return new ArrayList<>(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL));
    }

    private PkIndexEntry indexed(String id) throws IOException {
        return resident().stream().filter(row -> row.getValue().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void insertUpdateAndRelocateWriteTheEncodedBytes() throws Exception {
        final var single = document("single", "one", 0L);
        fs.insertIntoCollection(single, single.encodeLine());
        expectAppended(0L, "single", "one");
        fs.insertIntoCollection(document("plain", "two", 0L));
        expectAppended(0L, "plain", "two");
        assertPagesAsLegacy();

        final var bulk = new ArrayList<DbEntry>();
        for (var i = 0; i < 6; i++) {
            bulk.add(document("bulk-" + i, "value-" + i, i % 2));
            expectAppended(i % 2, "bulk-" + i, "value-" + i);
        }
        fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, bulk, DbEntry::getPage, DbEntry::encodeLine);
        assertPagesAsLegacy();

        final var updated = document("plain", "two-grown-" + "z".repeat(30), 0L);
        fs.updateFromCollection(updated, indexed("plain"), updated.encodeLine(), resident());
        expectRemovedFromFirstPage("plain");
        expectAppended(0L, "plain", "two-grown-" + "z".repeat(30));
        assertPagesAsLegacy();

        final var source = indexed("bulk-2");
        final var relocated = document("bulk-2", "moved", 1L);
        fs.relocate(relocated, source, 1L, relocated.encodeLine(), resident(), null);
        expectRemovedFromFirstPage("bulk-2");
        expectAppended(1L, "bulk-2", "moved");
        assertPagesAsLegacy();

        fs.deleteFromCollection(indexed("single"));
        expectRemovedFromFirstPage("single");
        assertPagesAsLegacy();
    }
}
