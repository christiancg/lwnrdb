package org.techhouse.fs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class WriteAheadLog {
    private static final Logger logger = Logger.logFor(WriteAheadLog.class);
    private static final Map<String, LogState> states = new ConcurrentHashMap<>();
    private static volatile CheckpointGuard guard = CheckpointGuard.NO_INDEX_WORK;
    private final Configuration configuration = Configuration.getInstance();
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private final FilePaths paths;

    private static final class LogState {
        private final String dbName;
        private final String collName;
        private final Set<Long> touchedPages = new HashSet<>();
        private long nextSeq = 1L;
        private long knownLength;
        private int recordsSinceCheckpoint;
        private boolean unapplied;

        private LogState(String dbName, String collName) {
            this.dbName = dbName;
            this.collName = collName;
        }
    }

    WriteAheadLog(FilePaths paths) {
        this.paths = paths;
    }

    Lock lock(String dbName, String collName) {
        return FileLocks.lockWrite(paths.writeAheadLog(dbName, collName));
    }

    long append(String dbName, String collName, WalCodec.RecordBuffer record, Collection<Long> pages)
            throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        var state = stateOf(log, dbName, collName);
        if (state.knownLength == 0L) {
            checkpoint(dbName, collName);
            state = stateOf(log, dbName, collName);
        }
        final var seq = state.nextSeq;
        write(log, state, record.header(seq), record.bytes(), record.size());
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
        state.touchedPages.addAll(pages);
        faultInjector.at(FaultPoint.WAL_APPENDED, subject(dbName, collName));
        return seq;
    }

    void abort(String dbName, String collName, long seq) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var state = stateOf(log, dbName, collName);
        final var frame = WalCodec.encodeAbort(state.nextSeq, seq);
        write(log, state, frame, new byte[0], 0);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
    }

    private static void write(File log, LogState state, byte[] header, byte[] payload, int payloadLength)
            throws IOException {
        var appended = false;
        try {
            try (var out = new FileOutputStream(log, true)) {
                out.write(header);
                out.write(payload, 0, payloadLength);
                SyncedWrites.content(out.getChannel(), log.toPath());
            }
            appended = true;
        } finally {
            if (!appended && log.length() != state.knownLength) {
                FileLocks.truncateTo(log, state.knownLength, "Could not roll back a partial append to the"
                        + " write-ahead log " + log.getPath() + "; the next startup cuts it");
            }
        }
        state.knownLength += header.length + payloadLength;
    }

    void markUnapplied(String dbName, String collName) {
        final var state = states.get(paths.writeAheadLog(dbName, collName).getAbsolutePath());
        if (state != null) {
            state.unapplied = true;
        }
    }

    void requireApplied(String dbName, String collName) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var known = states.get(log.getAbsolutePath());
        if (known == null || !known.unapplied) {
            return;
        }
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log);
        try {
            final var state = stateOf(log, dbName, collName);
            if (state.unapplied) {
                WalRedo.replay(paths, dbName, collName, WalCodec.decodeAll(Files.readAllBytes(log.toPath())).records());
                state.unapplied = false;
                logger.warning("Applied the logged writes of " + subject(dbName, collName) + " that an earlier write"
                        + " could not");
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    void track(String dbName, String collName) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var lock = FileLocks.lockWrite(log);
        try {
            stateOf(log, dbName, collName);
        } finally {
            lock.unlock();
        }
    }

    static void guardWith(CheckpointGuard checkpointGuard) {
        guard = checkpointGuard;
    }

    void checkpointIfFull(String dbName, String collName) {
        final var state = states.get(paths.writeAheadLog(dbName, collName).getAbsolutePath());
        if (state != null && state.knownLength > configuration.getWalCheckpointBytes()) {
            try {
                checkpoint(dbName, collName);
            } catch (IOException e) {
                logger.error("Could not checkpoint the full write-ahead log of " + subject(dbName, collName)
                        + "; it keeps its records and the next write or the shutdown retries", e);
            }
        }
    }

    void checkpoint(String dbName, String collName) throws IOException {
        if (!paths.collectionFolder(dbName, collName).isDirectory()) {
            return;
        }
        final var log = paths.writeAheadLog(dbName, collName);
        final var pkIndex = paths.pkIndexFile(dbName, collName);
        final var pkLock = FileLocks.lockWrite(pkIndex);
        final var walLock = FileLocks.lockWrite(log);
        try {
            requireApplied(dbName, collName);
            final var state = stateOf(log, dbName, collName);
            if (state.knownLength > 0L && state.recordsSinceCheckpoint == 0) {
                return;
            }
            if (state.recordsSinceCheckpoint > 0) {
                guard.persistIndexWork(dbName, collName);
            }
            final var group = new ForceGroup();
            for (final var page : state.touchedPages) {
                final var file = paths.collectionPage(dbName, collName, page);
                if (file.exists()) {
                    group.add(file.toPath());
                }
            }
            if (pkIndex.exists()) {
                group.add(pkIndex.toPath());
            }
            group.force();
            faultInjector.at(FaultPoint.WAL_CHECKPOINT_FORCED, subject(dbName, collName));
            final var bytes = WalCodec
                    .encode(new WalCheckpoint(state.nextSeq, dbName, collName, pkIndex.exists() ? pkIndex.length() : 0L,
                            DocumentPageStore.pageFileLengths(paths, dbName, collName)));
            FileLocks.rewriteFileAtomically(log.toPath(), bytes);
            state.nextSeq++;
            state.knownLength = bytes.length;
            state.recordsSinceCheckpoint = 0;
            state.touchedPages.clear();
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    void checkpointAll() {
        final var root = new File(paths.dbPath()).getAbsolutePath() + File.separator;
        for (final var entry : List.copyOf(states.entrySet())) {
            if (!entry.getKey().startsWith(root)) {
                continue;
            }
            final var state = entry.getValue();
            if (guard.holdsIndexWork(state.dbName, state.collName)) {
                logger.info("Leaving the write-ahead log of " + subject(state.dbName, state.collName) + " as it is:"
                        + " its field-index work is still pending, so the next startup replays it and finishes that"
                        + " work");
                continue;
            }
            try {
                checkpoint(state.dbName, state.collName);
            } catch (IOException e) {
                logger.error("Could not checkpoint the write-ahead log of " + subject(state.dbName, state.collName)
                        + "; the next startup replays it", e);
            }
        }
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        states.keySet().removeIf(path -> path.startsWith(prefix));
    }

    private static LogState stateOf(File log, String dbName, String collName) throws IOException {
        final var state = states.computeIfAbsent(log.getAbsolutePath(), _ -> new LogState(dbName, collName));
        final var length = log.exists() ? log.length() : 0L;
        if (length != state.knownLength) {
            reseed(log, state);
        }
        return state;
    }

    private static void reseed(File log, LogState state) throws IOException {
        state.touchedPages.clear();
        state.recordsSinceCheckpoint = 0;
        if (!log.exists()) {
            state.knownLength = 0L;
            return;
        }
        final var decoded = WalCodec.decodeAll(Files.readAllBytes(log.toPath()));
        if (decoded.checkpoint() == null) {
            state.knownLength = 0L;
            return;
        }
        if (decoded.cleanLength() != log.length()) {
            FileLocks.truncateTo(log, decoded.cleanLength(), "Could not cut the torn end of the write-ahead log "
                    + log.getPath() + "; the next startup cuts it");
        }
        for (final var record : decoded.records()) {
            for (final var change : record.changes()) {
                if (change.target() != WalTarget.PK_WRITE) {
                    state.touchedPages.add(change.page());
                }
            }
        }
        state.recordsSinceCheckpoint = decoded.framesSinceCheckpoint();
        state.nextSeq = Math.max(state.nextSeq, decoded.lastSeq() + 1);
        state.knownLength = log.length();
    }

    static String subject(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
