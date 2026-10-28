package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.test.TestGlobals;

public class AdminStrayCopyTest extends FileSystemTestBase {
    private static final String ID = "strayDb";

    private static File firstPage() {
        final var collection = Globals.ADMIN_DATABASES_COLLECTION_NAME;
        return new File(TestGlobals.PATH + File.separator + Globals.ADMIN_DB_NAME + File.separator + collection,
                collection + Globals.FILE_PAGE_SEPARATOR + 0 + Globals.DB_FILE_EXTENSION);
    }

    private void insertAdminRecord() throws Exception {
        final var data = new JsonObject();
        data.addProperty(Globals.PK_FIELD, ID);
        data.addProperty("v", "acknowledged");
        final var entry = DbEntry.fromJsonObject(Globals.ADMIN_DB_NAME, Globals.ADMIN_DATABASES_COLLECTION_NAME, data);
        entry.set_id(ID);
        entry.setPage(0);
        fs.insertIntoCollection(entry);
    }

    @Test
    public void test_startup_cuts_an_admin_copy_whose_intent_a_power_loss_lost_before_the_cache_loads()
            throws Exception {
        insertAdminRecord();
        final var indexedEnd = firstPage().length();
        Files.writeString(firstPage().toPath(), "{\"_id\":\"" + ID + "\",\"v\":\"never-acknowledged\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        try (var _ = RecordingForcer.install(true)) {
            fs.recoverWriteIntents();
        }

        assertEquals(indexedEnd, firstPage().length());
    }
}
