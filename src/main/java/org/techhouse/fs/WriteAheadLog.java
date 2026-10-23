package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
        private boolean groupBaseLogged;
        private WalWriteGroup group;

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
        if (state.group != null && state.group.failed()) {
            throw new IOException("An earlier write grouped with this one on " + subject(dbName, collName)
                    + " could not be made durable, so the group is refused");
        }
        if (state.group != null && !state.groupBaseLogged) {
            logGroupBase(dbName, collName, log, state);
        }
        final var seq = state.nextSeq;
        write(log, state, record.header(seq), record.bytes(), record.size(), state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
        state.touchedPages.addAll(pages);
        if (state.group != null) {
            state.group.recorded();
        }
        faultInjector.at(FaultPoint.WAL_APPENDED, subject(dbName, collName));
        return seq;
    }

    void abort(String dbName, String collName, long seq) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var state = stateOf(log, dbName, collName);
        final var frame = WalCodec.encodeAbort(state.nextSeq, seq);
        write(log, state, frame, new byte[0], 0, state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
    }

    private void logGroupBase(String dbName, String collName, File log, LogState state) throws IOException {
        final var pkIndex = paths.pkIndexFile(dbName, collName);
        final var frame = WalCodec.encodeGroupBase(state.nextSeq, pkIndex.exists() ? pkIndex.length() : 0L);
        write(log, state, frame, new byte[0], 0, true);
        state.nextSeq++;
        state.groupBaseLogged = true;
        state.group.restartFrom(state.knownLength);
    }

    void openGroup(String dbName, String collName) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var lock = FileLocks.lockWrite(log);
        try {
            final var state = stateOf(log, dbName, collName);
            if (state.group == null) {
                state.group = new WalWriteGroup(state.knownLength);
            }
        } finally {
            lock.unlock();
        }
    }

    boolean closeGroup(String dbName, String collName) throws IOException {
        final var log = paths.writeAheadLog(dbName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log);
        try {
            final var state = stateOf(log, dbName, collName);
            if (state.group == null) {
                return true;
            }
            try {
                return settle(dbName, collName, log, state);
            } finally {
                state.group = null;
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    WalWriteGroup openGroupOf(String dbName, String collName) {
        final var state = states.get(paths.writeAheadLog(dbName, collName).getAbsolutePath());
        return state == null ? null : state.group;
    }

    private boolean settle(String dbName, String collName, File log, LogState state) {
        final var group = state.group;
        if (group.failed()) {
            takeBack(dbName, collName, log, state);
            return false;
        }
        if (!group.holdsWork()) {
            return true;
        }
        faultInjector.at(FaultPoint.WAL_GROUP_APPENDED, subject(dbName, collName));
        try {
            forceLog(log);
        } catch (IOException e) {
            logger.error("Could not force the write-ahead log of " + subject(dbName, collName) + " for a group of "
                    + group.records() + " write(s); they are taken back and every one of them fails", e);
            group.fail();
            takeBack(dbName, collName, log, state);
            return false;
        }
        faultInjector.at(FaultPoint.WAL_GROUP_FORCED, subject(dbName, collName));
        if (!group.applyBlanks()) {
            state.unapplied = true;
        }
        group.restartFrom(state.knownLength);
        return true;
    }

    private void takeBack(String dbName, String collName, File log, LogState state) {
        final var group = state.group;
        if (group.takeBack(log, paths.pkIndexFile(dbName, collName))) {
            state.recordsSinceCheckpoint = Math.max(0, state.recordsSinceCheckpoint - group.records());
            state.knownLength = group.logLengthBefore();
        } else {
            state.unapplied = true;
            logger.error("Could not take back a failed group of writes to " + subject(dbName, collName) + "; they are"
                    + " applied before the next write to the collection, or at the next startup");
        }
        group.restartFrom(state.knownLength);
    }

    private static void forceLog(File log) throws IOException {
        try (var channel = FileChannel.open(log.toPath(), StandardOpenOption.WRITE)) {
            SyncedWrites.content(channel, log.toPath());
        }
    }

    private static void write(File log, LogState state, byte[] header, byte[] payload, int payloadLength, boolean force)
            throws IOException {
        var appended = false;
        try {
            try (var channel = FileChannel.open(log.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                final var buffers = new ByteBuffer[]{ByteBuffer.wrap(header),
                        ByteBuffer.wrap(payload, 0, payloadLength)};
                var unwritten = (long) header.length + payloadLength;
                while (unwritten > 0) {
                    unwritten -= channel.write(buffers);
                }
                if (force) {
                    SyncedWrites.content(channel, log.toPath());
                }
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
            if (state.unapplied && !log.exists()) {
                state.unapplied = false;
                logger.warning("The write-ahead log of " + subject(dbName, collName) + " is gone, so the writes it"
                        + " held can no longer be applied");
            } else if (state.unapplied) {
                if (state.group != null && !settle(dbName, collName, log, state)) {
                    throw new IOException("Could not make the open write group of " + subject(dbName, collName)
                            + " durable before applying its logged writes");
                }
                forceLog(log);
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
            if (state.group != null && !settle(dbName, collName, log, state)) {
                throw new IOException("Could not force the open write group of " + subject(dbName, collName)
                        + " before a checkpoint; its writes were taken back");
            }
            if (state.knownLength > 0L && state.recordsSinceCheckpoint == 0 && !state.groupBaseLogged) {
                return;
            }
            final var pending = List.copyOf(guard.pendingIds(dbName, collName));
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
            FieldIndexLog.forceUnforced(paths.collectionFolder(dbName, collName), group);
            group.force();
            faultInjector.at(FaultPoint.WAL_CHECKPOINT_FORCED, subject(dbName, collName));
            final var checkpointFrame = WalCodec
                    .encode(new WalCheckpoint(state.nextSeq, dbName, collName, pkIndex.exists() ? pkIndex.length() : 0L,
                            DocumentPageStore.pageFileLengths(paths, dbName, collName)));
            final var bytes = pending.isEmpty()
                    ? checkpointFrame
                    : concat(checkpointFrame, WalCodec.encodePending(state.nextSeq + 1, pending));
            FileLocks.rewriteFileAtomically(log.toPath(), bytes);
            state.nextSeq += pending.isEmpty() ? 1 : 2;
            state.knownLength = bytes.length;
            state.recordsSinceCheckpoint = 0;
            state.groupBaseLogged = false;
            state.touchedPages.clear();
            if (state.group != null) {
                state.group.restartFrom(state.knownLength);
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        final var joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    void checkpointAll(long deadline) {
        final var root = new File(paths.dbPath()).getAbsolutePath() + File.separator;
        final var logs = states.entrySet().stream().filter(entry -> entry.getKey().startsWith(root))
                .map(Map.Entry::getValue).toList();
        for (var i = 0; i < logs.size(); i++) {
            if (System.currentTimeMillis() >= deadline) {
                logger.warning("The shutdown budget ran out with " + (logs.size() - i) + " write-ahead log(s) not"
                        + " checkpointed; the next startup replays them");
                return;
            }
            checkpointAtShutdown(logs.get(i));
        }
    }

    private void checkpointAtShutdown(LogState state) {
        if (guard.holdsIndexWork(state.dbName, state.collName)) {
            logger.info("Leaving the write-ahead log of " + subject(state.dbName, state.collName) + " as it is:"
                    + " its field-index work is still pending, so the next startup replays it and finishes that"
                    + " work");
            return;
        }
        try {
            checkpoint(state.dbName, state.collName);
        } catch (IOException e) {
            logger.error("Could not checkpoint the write-ahead log of " + subject(state.dbName, state.collName)
                    + "; the next startup replays it", e);
        }
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        states.keySet().removeIf(path -> path.startsWith(prefix));
    }

    private static LogState stateOf(File log, String dbName, String collName) throws IOException {
        final var state = states.computeIfAbsent(log.getAbsolutePath(), _ -> new LogState(dbName, collName));
        if (log.length() != state.knownLength) {
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
        state.groupBaseLogged = decoded.groupBase() != WalCodec.NO_GROUP_BASE;
        state.nextSeq = Math.max(state.nextSeq, decoded.lastSeq() + 1);
        state.knownLength = log.length();
    }

    static String subject(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
