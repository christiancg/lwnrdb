package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.log.Logger;

final class PageRegions {
    private static final Logger logger = Logger.logFor(PageRegions.class);

    private PageRegions() {
    }

    static void truncateTo(File file, long length) {
        truncateTo(file, length, "Could not roll back the page append for " + file.getAbsolutePath()
                + " after its index write failed; run REINDEX on this collection");
    }

    static void truncateTo(File file, long length, String failureMessage) {
        try (var channel = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
            channel.setLength(length);
        } catch (IOException e) {
            logger.error(failureMessage, e);
        }
    }

    static byte[] readRegion(RandomAccessFile writer, long from, long to) throws IOException {
        final var length = (int) (to - from);
        if (length <= 0) {
            return new byte[0];
        }
        final var buffer = new byte[length];
        writer.seek(from);
        writer.readFully(buffer, 0, length);
        return buffer;
    }

    static void restoreRegion(RandomAccessFile writer, long from, byte[] region, long originalLength) {
        try {
            if (region.length > 0) {
                writer.seek(from);
                writer.write(region, 0, region.length);
            }
            writer.setLength(originalLength);
        } catch (IOException restoreFailure) {
            logger.error("Could not restore the page after a failed page or index write; run REINDEX on this"
                    + " collection", restoreFailure);
        }
    }

    static boolean shiftOtherEntriesToStart(RandomAccessFile writer, PkIndexEntry pkIndexEntry, long totalFileLength)
            throws IOException {
        final int otherEntriesLength = (int) (totalFileLength - pkIndexEntry.getPosition() - pkIndexEntry.getLength());
        if (otherEntriesLength <= 0) {
            return false;
        }
        writer.seek(pkIndexEntry.getPosition() + pkIndexEntry.getLength());
        byte[] buffer = new byte[otherEntriesLength];
        writer.readFully(buffer, 0, otherEntriesLength);
        writer.seek(pkIndexEntry.getPosition());
        writer.write(buffer, 0, otherEntriesLength);
        return true;
    }
}
