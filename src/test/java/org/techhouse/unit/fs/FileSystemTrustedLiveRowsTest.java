package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestGlobals;

public class FileSystemTrustedLiveRowsTest extends FileSystemTestBase {
    private static DbEntry document(String id, String value) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", value);
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
    }

    private void assertTrustedEqualsFolded(String after) throws IOException {
        final var trusted = fs.trustedLiveRows(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(trusted.isPresent(), "the live-row count is known after " + after);
        assertEquals(fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).size(), trusted.getAsLong(), after);
    }

    @Test
    public void test_a_missing_index_holds_no_rows() {
        fs.deleteCollectionFiles(TestGlobals.DB, TestGlobals.COLL);
        assertEquals(0L, fs.trustedLiveRows(TestGlobals.DB, TestGlobals.COLL).orElseThrow());
    }

    @Test
    public void test_the_live_row_count_follows_every_write_path() throws IOException {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < 3; i++) {
            rows.add(fs.insertIntoCollection(document("single-" + i, "a")));
        }
        assertTrustedEqualsFolded("inserts");

        final var updated = document("single-0", "b");
        rows.set(0, fs.updateFromCollection(updated, rows.getFirst()));
        assertTrustedEqualsFolded("an update");

        final var grown = document("single-1", "c".repeat(200));
        rows.set(1, fs.relocate(grown, rows.get(1), 1L, grown.encodeLine(), null, null));
        assertTrustedEqualsFolded("a relocation");

        fs.deleteFromCollection(rows.get(2));
        assertTrustedEqualsFolded("a delete");

        final var bulk = new ArrayList<DbEntry>();
        for (var i = 0; i < 5; i++) {
            bulk.add(document("bulk-" + i, "d"));
        }
        final var inserted = fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, bulk);
        assertTrustedEqualsFolded("a bulk insert");

        inserted.forEach(entry -> entry.getData().addProperty("v", "e"));
        fs.bulkUpdateFromCollection(TestGlobals.DB, TestGlobals.COLL, inserted.subList(0, 2));
        assertTrustedEqualsFolded("a bulk update");

        final var current = fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL);
        fs.writeBatch(TestGlobals.DB, TestGlobals.COLL, List.of(document("batch-0", "f"), document("batch-1", "f")),
                DbEntry::getPage, DbEntry::encodeLine, List.of(current.getLast()));
        assertTrustedEqualsFolded("a batch of inserts and a delete");
    }

    @Test
    public void test_the_live_row_count_survives_compaction() throws IOException {
        var row = fs.insertIntoCollection(document("hot", "0"));
        fs.insertIntoCollection(document("cold", "0"));
        for (var i = 1; i <= Globals.PK_LOG_MIN_DEAD_ROWS + 10; i++) {
            row = fs.updateFromCollection(document("hot", Integer.toString(i)), row);
        }

        assertTrustedEqualsFolded("compactions");
    }
}
