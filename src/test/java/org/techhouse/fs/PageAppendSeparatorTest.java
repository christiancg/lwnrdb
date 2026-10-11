package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PageAppendSeparatorTest {
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, id);
        data.addProperty("v", id + "-value");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.setPage(0L);
        return entry;
    }

    private static File pageFile() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + "-0" + Globals.DB_FILE_EXTENSION);
    }

    private static void dropTrailingLineEnd() throws IOException {
        try (var raf = new RandomAccessFile(pageFile(), "rw")) {
            raf.setLength(raf.length() - Globals.NEWLINE.length());
        }
    }

    private PkIndexEntry pkOf(String id) throws IOException {
        return fs.readWholePkIndexFile(TestGlobals.DB, TestGlobals.COLL).stream()
                .filter(entry -> entry.getValue().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void test_an_insert_after_a_lost_line_end_starts_a_new_line() throws Exception {
        fs.insertIntoCollection(entry("first"));
        dropTrailingLineEnd();

        fs.insertIntoCollection(entry("second"));

        final var page = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0L);
        assertEquals(List.of("first", "second"), page.keySet().stream().sorted().toList(),
                "a scan must see both records, not only the first of a glued line");
        assertEquals("second", fs.getById(pkOf("second").detachedCopy()).get_id());
        assertEquals("first", fs.getById(pkOf("first").detachedCopy()).get_id());
    }

    @Test
    public void test_a_bulk_insert_after_a_lost_line_end_starts_a_new_line() throws Exception {
        fs.insertIntoCollection(entry("first"));
        dropTrailingLineEnd();

        fs.bulkInsertIntoCollection(TestGlobals.DB, TestGlobals.COLL, List.of(entry("second"), entry("third")));

        final var page = fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0L);
        assertEquals(List.of("first", "second", "third"), page.keySet().stream().sorted().toList());
        assertEquals("third", fs.getById(pkOf("third").detachedCopy()).get_id());
    }

    @Test
    public void test_an_insert_onto_a_complete_page_adds_no_separator() throws Exception {
        final var first = fs.insertIntoCollection(entry("first"));

        final var second = fs.insertIntoCollection(entry("second"));

        assertEquals(first.getPosition() + first.getLength(), second.getPosition());
    }

    @Test
    public void test_startup_heal_restores_a_lost_line_end_of_an_indexed_record() throws Exception {
        fs.insertIntoCollection(entry("first"));
        final var lengthBefore = pageFile().length();
        dropTrailingLineEnd();

        fs.healTornPageTails(TestGlobals.DB, TestGlobals.COLL);

        assertEquals(lengthBefore, pageFile().length());
        assertEquals(List.of("first"),
                List.copyOf(fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0L).keySet()));
    }
}
