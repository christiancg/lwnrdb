package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.IntentLogs;
import org.techhouse.test.TestGlobals;

public class FileSystemWriteIntentTest extends FileSystemTestBase {

    private static int openIntents() throws IOException {
        return IntentLogs.openIntentsUnder(TestGlobals.PATH);
    }

    private PkIndexEntry insert(String id, String value) throws Exception {
        final var data = new JsonObject();
        data.addProperty("v", value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id(id);
        return fs.insertIntoCollection(entry);
    }

    private DbEntry updated(PkIndexEntry existing) {
        final var data = new JsonObject();
        data.addProperty("v", "uno");
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.set_id(existing.getValue());
        entry.setPage(existing.getPage());
        return entry;
    }

    private static File page0() {
        return new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR
                + TestGlobals.COLL + Globals.FILE_SEPARATOR + TestGlobals.COLL + Globals.FILE_PAGE_SEPARATOR + "0"
                + Globals.DB_FILE_EXTENSION);
    }

    private static File pkIndex() {
        return new File(page0().getParentFile(), TestGlobals.COLL + "-pk.idx");
    }

    private void makeThePkIndexUnwritable() throws Exception {
        Files.delete(pkIndex().toPath());
        assertTrue(pkIndex().mkdir(), "the test needs the pk index path to be unwritable");
    }

    @Test
    public void aCompletedDeleteLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");

        fs.deleteFromCollection(first);

        assertEquals(0, openIntents());
    }

    @Test
    public void aDeleteWhosePkWriteFailsLeavesThePageAndNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var before = Files.readString(page0().toPath());
        makeThePkIndexUnwritable();

        assertThrows(IOException.class, () -> fs.deleteFromCollection(first));

        assertEquals(before, Files.readString(page0().toPath()));
        assertEquals(0, openIntents());
    }

    @Test
    public void aCompletedUpdateLeavesNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");

        fs.updateFromCollection(updated(first), first);

        assertEquals(0, openIntents());
    }

    @Test
    public void anUpdateWhosePkWriteFailsLeavesThePageAndNoMarker() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var before = Files.readString(page0().toPath());
        makeThePkIndexUnwritable();

        assertThrows(Exception.class, () -> fs.updateFromCollection(updated(first), first));

        assertEquals(before, Files.readString(page0().toPath()));
        assertEquals(0, openIntents());
    }

    @Test
    public void aCompletedRelocationLeavesNoMarkerAndAHoleWhereTheOldCopyWas() throws Exception {
        final var first = insert("1", "one");
        insert("2", "two");
        final var moved = updated(first);

        final var relocated = fs.relocate(moved, first, 1, moved.encodeLine(), null, null);

        assertEquals(0, openIntents());
        assertEquals(1L, relocated.getPage());
        assertEquals("uno", fs.getById(relocated).getData().get("v").asJsonString().getValue());
        final var page = Files.readAllBytes(page0().toPath());
        assertEquals(' ', page[(int) first.getPosition()], "the superseded copy is blanked in place");
        assertEquals(1, fs.readWholeCollectionPage(TestGlobals.DB, TestGlobals.COLL, 0).size());
    }

    @Test
    public void aCompletedUpdateAppendsTheNewCopyAndBlanksTheOldOne() throws Exception {
        final var first = insert("1", "one");
        final var second = insert("2", "two");

        final var updated = fs.updateFromCollection(updated(first), first);

        assertTrue(updated.getPosition() > second.getPosition(), "the new copy is appended after every record");
        final var page = Files.readAllBytes(page0().toPath());
        for (var at = first.getPosition(); at < first.getPosition() + first.getLength()
                - Globals.NEWLINE.length(); at++) {
            assertEquals(' ', page[(int) at]);
        }
        assertEquals("two", fs.getById(second).getData().get("v").asJsonString().getValue(), "no other record moved");
        assertEquals("uno", fs.getById(updated).getData().get("v").asJsonString().getValue());
    }
}
