package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.ChunkedChannelWrites;
import org.techhouse.log.Logger;

final class FailedForceRebuild {
    private static final Logger logger = Logger.logFor(FailedForceRebuild.class);
    private static final String REBUILD_SUFFIX = ".repair";

    private final FilePaths paths;
    private final String dbName;
    private final String collName;
    private final File log;
    private WalCodec.Decoded decoded;

    private FailedForceRebuild(FilePaths paths, String dbName, String collName, File log) {
        this.paths = paths;
        this.dbName = dbName;
        this.collName = collName;
        this.log = log;
    }

    static void rebuildFailed(FilePaths paths, String dbName, String collName, File log) throws IOException {
        final var failed = ForceFailures.in(paths.collectionFolder(dbName, collName));
        if (failed.isEmpty()) {
            return;
        }
        final var rebuild = new FailedForceRebuild(paths, dbName, collName, log);
        final var pages = rebuild.pageFiles();
        for (final var file : failed) {
            if (!file.isFile()) {
                ForceFailures.cleared(file.toPath());
            } else if (pages.contains(file) || rebuild.isPkIndex(file) || isFieldIndexLog(file)) {
                rebuild.rebuild(file);
            }
        }
    }

    private Set<File> pageFiles() throws IOException {
        final var pages = new HashSet<File>();
        for (final var page : DocumentPageStore.pageFileLengths(paths, dbName, collName).keySet()) {
            pages.add(paths.collectionPage(dbName, collName, page).getAbsoluteFile());
        }
        return pages;
    }

    private boolean isPkIndex(File file) {
        return file.equals(paths.pkIndexFile(dbName, collName).getAbsoluteFile());
    }

    private static boolean isFieldIndexLog(File file) {
        return file.getName().endsWith(Globals.INDEX_LOG_FILE_EXTENSION);
    }

    private void rebuild(File file) throws IOException {
        final var lock = lockFor(file);
        try {
            if (!file.isFile()) {
                ForceFailures.cleared(file.toPath());
                return;
            }
            final var temp = file.toPath().resolveSibling(file.getName() + REBUILD_SUFFIX);
            OwnerOnly.ensureOwnerOnlyFile(temp);
            try (var source = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                    var copy = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                copyAll(source, copy);
                final var reapplied = reapplyLogged(file, copy);
                SyncedWrites.content(copy, temp);
                logger.warning("Rebuilt " + file.getName() + " of " + WriteAheadLog.subject(dbName, collName)
                        + " into a new file, re-applying " + reapplied + " logged change(s), because an earlier"
                        + " force of it failed and a retried force of the same file is not trusted");
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            FileLocks.moveIntoPlaceUnsynced(temp, file.toPath());
            PageLengths.forget(file);
            if (isPkIndex(file)) {
                PkIndexStore.forgetState(file);
            }
            try {
                SyncedWrites.verifyFolderSync(file.toPath().toAbsolutePath().getParent());
            } catch (IOException e) {
                ForceFailures.failed(file.toPath());
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private Lock lockFor(File file) throws IOException {
        final var guarded = isFieldIndexLog(file)
                ? new File(file.getPath().substring(0, file.getPath().length() - Globals.INDEX_LOG_SUFFIX.length()))
                : file;
        final var lock = FileLocks.lockFor(guarded).writeLock();
        try {
            if (lock.tryLock(Globals.WAL_BACKGROUND_CHECKPOINT_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return lock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new IOException("Could not lock " + guarded.getName() + " to rebuild " + file.getName()
                + " after its failed force; the write-ahead log keeps its records until a later checkpoint");
    }

    private static void copyAll(FileChannel source, FileChannel copy) throws IOException {
        final var size = source.size();
        var copied = 0L;
        while (copied < size) {
            final var moved = copy.transferFrom(source, copied, size - copied);
            if (moved <= 0) {
                throw new IOException("Could not copy past byte " + copied + " of " + size);
            }
            copied += moved;
        }
    }

    private int reapplyLogged(File file, FileChannel copy) throws IOException {
        if (isFieldIndexLog(file) || !log.isFile()) {
            return 0;
        }
        if (decoded == null) {
            decoded = WalCodec.decodeAll(Files.readAllBytes(log.toPath()));
        }
        final var target = file.getAbsoluteFile();
        var reapplied = 0;
        for (final var record : decoded.records()) {
            for (final var change : record.changes()) {
                if (!WalRedo.skipped(decoded, record, change) && target.equals(fileOf(change))) {
                    apply(copy, change);
                    reapplied++;
                }
            }
        }
        return reapplied;
    }

    private File fileOf(WalChange change) {
        return (change.target() == WalTarget.PK_WRITE
                ? paths.pkIndexFile(dbName, collName)
                : paths.collectionPage(dbName, collName, change.page())).getAbsoluteFile();
    }

    private static void apply(FileChannel copy, WalChange change) throws IOException {
        if (change.target() == WalTarget.PAGE_BLANK) {
            PageHoles.blank(copy, change.offset(), change.length());
        } else {
            ChunkedChannelWrites.writeAt(copy, change.offset(), change.bytes(), 0, change.bytes().length);
        }
    }
}
