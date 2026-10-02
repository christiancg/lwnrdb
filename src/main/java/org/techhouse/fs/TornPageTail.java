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
    private static final int SCAN_CHUNK = 8192;
    private static final byte LINE_END = (byte) Globals.NEWLINE.charAt(Globals.NEWLINE.length() - 1);

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
            if (endOfLastCompleteLine(file) < 0) {
                continue;
            }
            if (entries == null) {
                entries = pkEntries.read();
            }
            heal(file, page, entries);
        }
    }

    static boolean heal(File page, long pageNumber, List<PkIndexEntry> entries) throws IOException {
        final var lock = FileLocks.lockFor(page).writeLock();
        lock.lock();
        try {
            final var cut = endOfLastCompleteLine(page);
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
            PageRegions.truncateTo(page, cut,
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
        final var indexedEnd = entries.stream().filter(entry -> entry.getPage() == pageNumber)
                .mapToLong(entry -> entry.getPosition() + entry.getLength()).max().orElse(-1);
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

    private static long endOfLastCompleteLine(File page) throws IOException {
        try (var reader = new RandomAccessFile(page, Globals.R_PERMISSIONS)) {
            final var length = reader.length();
            if (length == 0) {
                return -1;
            }
            reader.seek(length - 1);
            if (reader.readByte() == LINE_END) {
                return -1;
            }
            final var buffer = new byte[SCAN_CHUNK];
            var end = length;
            while (end > 0) {
                final var start = Math.max(0, end - SCAN_CHUNK);
                final var read = (int) (end - start);
                reader.seek(start);
                reader.readFully(buffer, 0, read);
                for (var i = read - 1; i >= 0; i--) {
                    if (buffer[i] == LINE_END) {
                        return start + i + 1;
                    }
                }
                end = start;
            }
            return 0;
        }
    }
}
