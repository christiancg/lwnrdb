package org.techhouse;

import java.io.IOException;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.ex.IncompatibleStorageFormatException;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.FormatStamp;
import org.techhouse.fs.MalformedFormatStampException;
import org.techhouse.log.Logger;

public final class StorageFormatGuard {
    private static final Logger logger = Logger.logFor(StorageFormatGuard.class);
    private static final String UNSTAMPED_WRITER = "a release before 0.1.0";
    private static final String RELEASE_PREFIX = "lwnrdb ";

    private StorageFormatGuard() {
    }

    public static void refuseIncompatibleFolder(FileSystem fs) throws IOException {
        final var file = fs.formatStamp();
        final var stamp = readStamp(fs);
        if (stamp == null && !file.holdsData()) {
            return;
        }
        final var found = stamp == null
                ? new FormatStamp(Globals.UNSTAMPED_STORAGE_FORMAT_VERSION, UNSTAMPED_WRITER)
                : stamp;
        if (found.formatVersion() > Globals.STORAGE_FORMAT_VERSION) {
            throw new IncompatibleStorageFormatException(newerFormat(fs, found));
        }
        if (found.formatVersion() < Globals.STORAGE_FORMAT_VERSION) {
            throw new IncompatibleStorageFormatException(olderFormat(fs, found));
        }
    }

    public static void stampIfUnstamped(FileSystem fs) throws IOException {
        final var file = fs.formatStamp();
        if (file.read() == null) {
            file.write(FormatStamp.current());
            logger.info("Stamped " + file.path().toAbsolutePath() + " with storage format "
                    + Globals.STORAGE_FORMAT_VERSION);
        }
    }

    private static FormatStamp readStamp(FileSystem fs) throws IncompatibleStorageFormatException {
        try {
            return fs.formatStamp().read();
        } catch (MalformedFormatStampException e) {
            throw new IncompatibleStorageFormatException(e.getMessage(), e);
        } catch (IOException e) {
            throw new IncompatibleStorageFormatException("Could not read the storage format stamp at "
                    + fs.formatStamp().path().toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private static String holds(FileSystem fs, FormatStamp stamp) {
        return fs.formatStamp().path().toAbsolutePath() + " says this folder holds storage format "
                + stamp.formatVersion() + " (written by " + writerOf(stamp) + "), but this release ("
                + ServerVersion.current() + ") reads only format " + Globals.STORAGE_FORMAT_VERSION + ". ";
    }

    private static String writerOf(FormatStamp stamp) {
        return UNSTAMPED_WRITER.equals(stamp.writtenBy()) ? UNSTAMPED_WRITER : RELEASE_PREFIX + stamp.writtenBy();
    }

    private static String newerFormat(FileSystem fs, FormatStamp stamp) {
        return holds(fs, stamp) + "Run " + RELEASE_PREFIX + stamp.writtenBy() + " or newer on this folder.";
    }

    private static String olderFormat(FileSystem fs, FormatStamp stamp) {
        return holds(fs, stamp) + "Dump it with " + writerOf(stamp)
                + " (java -jar lwnrdb.jar --dump ... > backup.jsonl), then start this release on an empty filePath"
                + " and restore it (--restore < backup.jsonl).";
    }
}
