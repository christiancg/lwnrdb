package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class TornPageTail {
    private static final Logger logger = Logger.logFor(TornPageTail.class);

    private TornPageTail() {
    }

    interface PkEntries {
        List<PkIndexEntry> read() throws IOException;
    }

    static void healAll(FilePaths paths, String dbName, String collName, Iterable<Long> pages, PkEntries pkEntries)
            throws IOException {
        List<PkIndexEntry> entries = null;
        for (final var page : pages) {
            final var file = paths.collectionPage(dbName, collName, page);
            if (FileLocks.endOfLastCompleteLine(file) < 0) {
                continue;
            }
            if (entries == null) {
                entries = pkEntries.read();
            }
            heal(file, page, entries);
        }
    }

    static boolean heal(File page, long pageNumber, List<PkIndexEntry> entries) throws IOException {
        final var lock = FileLocks.lockWrite(page);
        try {
            final var cut = FileLocks.endOfLastCompleteLine(page);
            if (cut < 0) {
                return false;
            }
            if (restoreMissingLineEnd(page, pageNumber, entries)) {
                return true;
            }
            for (final var entry : entries) {
                if (entry.getPage() == pageNumber && entry.getPosition() + entry.getLength() > cut) {
                    logger.error("Page " + page.getName() + " does not end in a newline, but its last "
                            + (page.length() - cut) + " byte(s) are indexed by " + entry.getValue()
                            + "; leaving the page untouched");
                    return false;
                }
            }
            final var torn = page.length() - cut;
            FileLocks.truncateTo(page, cut,
                    "Could not truncate the torn tail of " + page.getAbsolutePath() + "; the next write may be lost");
            logger.warning("Truncated a torn " + torn + "-byte tail from " + page.getName()
                    + ": it was left by an interrupted write and no index entry refers to it");
            return true;
        } finally {
            lock.unlock();
        }
    }

    private static boolean restoreMissingLineEnd(File page, long pageNumber, List<PkIndexEntry> entries)
            throws IOException {
        final var indexedEnd = OrphanedPageRecords.indexedEndOf(entries, pageNumber);
        final var lineEnd = Globals.NEWLINE.getBytes(StandardCharsets.UTF_8);
        final var missing = indexedEnd - page.length();
        if (missing <= 0 || missing > lineEnd.length) {
            return false;
        }
        final var present = lineEnd.length - (int) missing;
        try (var writer = new RandomAccessFile(page, Globals.RW_PERMISSIONS)) {
            if (present > 0 && !endsWith(writer, Arrays.copyOf(lineEnd, present))) {
                return false;
            }
            writer.seek(writer.length());
            writer.write(lineEnd, present, (int) missing);
            SyncedWrites.content(writer.getChannel(), page.toPath());
        } finally {
            PageTails.forget(page);
        }
        logger.warning("Restored the missing line end of the last record in " + page.getName()
                + ": the record was indexed, so only its terminator was lost");
        return true;
    }

    private static boolean endsWith(RandomAccessFile file, byte[] suffix) throws IOException {
        if (file.length() < suffix.length) {
            return false;
        }
        final var tail = new byte[suffix.length];
        file.seek(file.length() - suffix.length);
        file.readFully(tail);
        return Arrays.equals(tail, suffix);
    }
}
