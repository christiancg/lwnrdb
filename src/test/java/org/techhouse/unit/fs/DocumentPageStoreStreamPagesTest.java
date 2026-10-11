package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.management.UnixOperatingSystemMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.test.TestGlobals;

public class DocumentPageStoreStreamPagesTest extends FileSystemTestBase {
    private static final long[] PAGES = {2L, 10L, 0L, 1L};
    private static final int REPETITIONS = 200;
    private static final int ALLOWED_DESCRIPTOR_DRIFT = 8;

    private void insertOnPage(long page) throws IOException {
        final var data = new JsonObject();
        data.addProperty("_id", "doc" + page);
        data.addProperty("page", page);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
        entry.setPage(page);
        fs.insertIntoCollection(entry);
    }

    @Test
    public void stream_pages_returns_every_page_in_page_order() throws IOException {
        for (final var page : PAGES) {
            insertOnPage(page);
        }
        final List<Long> emitted = new ArrayList<>();
        try (var pages = fs.streamPages(TestGlobals.DB, TestGlobals.COLL)) {
            pages.forEach(page -> page.values()
                    .forEach(entry -> emitted.add(entry.getData().get("page").asJsonNumber().getValue().longValue())));
        }
        assertEquals(List.of(0L, 1L, 2L, 10L), emitted,
                "a folder scan must walk the pages numerically, like the page-metadata path does");
    }

    @Test
    public void stream_pages_on_a_missing_folder_is_empty() throws IOException {
        try (var pages = fs.streamPages(TestGlobals.DB, "neverCreated")) {
            assertEquals(0, pages.count());
        }
    }

    @Test
    public void stream_pages_does_not_hold_the_directory_open() throws IOException {
        final var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        assumeTrue(operatingSystem instanceof UnixOperatingSystemMXBean,
                "open descriptor counts are only readable through the Unix operating system MXBean");
        final var unix = (UnixOperatingSystemMXBean) operatingSystem;
        for (final var page : PAGES) {
            insertOnPage(page);
        }
        consumeWithoutClosing();
        final var before = unix.getOpenFileDescriptorCount();
        for (var i = 0; i < REPETITIONS; i++) {
            consumeWithoutClosing();
        }
        final var leaked = unix.getOpenFileDescriptorCount() - before;
        assertTrue(leaked <= ALLOWED_DESCRIPTOR_DRIFT,
                REPETITIONS + " unclosed page streams leaked " + leaked + " file descriptors");
    }

    private void consumeWithoutClosing() throws IOException {
        fs.streamPages(TestGlobals.DB, TestGlobals.COLL).forEach(_ -> {
        });
    }
}
