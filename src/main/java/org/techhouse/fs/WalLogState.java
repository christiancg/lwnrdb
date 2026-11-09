package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class WalLogState {
    private static final Map<String, WalLogState> states = new ConcurrentHashMap<>();

    final String dbName;
    final String collName;
    final String path;
    final Set<Long> touchedPages = new HashSet<>();
    final Set<Long> openRewrites = ConcurrentHashMap.newKeySet();
    long nextSeq = 1L;
    volatile long knownLength;
    long allocatedLength;
    int recordsSinceCheckpoint;
    boolean unapplied;
    boolean groupBaseLogged;
    boolean pendingLogged;
    volatile long pendingFrameBytes;
    WalWriteGroup group;
    volatile long lastWriteMillis;
    int checkpointFailures;
    volatile long checkpointRetryAtMillis;
    private volatile boolean seeded;

    private WalLogState(String dbName, String collName, String path) {
        this.dbName = dbName;
        this.collName = collName;
        this.path = path;
    }

    void refuseWhileRewriting(String subject) throws RewriteOpenException {
        if (!openRewrites.isEmpty()) {
            throw new RewriteOpenException("Did not checkpoint " + subject + " while page(s) " + openRewrites
                    + " are rewritten: it would drop their REWRITE_BEGIN");
        }
    }

    long lengthBeyondPending() {
        return knownLength - pendingFrameBytes;
    }

    boolean endGroup() {
        if (group != null) {
            group.dropChannel();
        }
        group = null;
        return true;
    }

    static WalLogState known(String path) {
        return states.get(path);
    }

    static Collection<WalLogState> all() {
        return states.values();
    }

    static WalLogState of(File log, String path, String dbName, String collName) throws IOException {
        final var known = states.get(path);
        if (known != null && known.seeded) {
            return known;
        }
        final var state = states.computeIfAbsent(path, _ -> new WalLogState(dbName, collName, path));
        if (!state.seeded) {
            state.reseed(log);
        }
        return state;
    }

    void reseedOnNextUse() {
        seeded = false;
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        states.entrySet().removeIf(entry -> entry.getKey().startsWith(prefix) && entry.getValue().endGroup());
    }

    private void reseed(File log) throws IOException {
        if (group != null) {
            group.dropChannel();
        }
        touchedPages.clear();
        final var bytes = log.exists() ? Files.readAllBytes(log.toPath()) : null;
        final var decoded = bytes == null ? null : WalCodec.decodeAll(bytes);
        if (decoded == null || decoded.checkpoint() == null) {
            recordsSinceCheckpoint = 0;
            pendingLogged = false;
            pendingFrameBytes = 0L;
            knownLength = 0L;
            allocatedLength = 0L;
            seeded = true;
            return;
        }
        final var cleanLength = decoded.cleanLength();
        if (!WalWriteGroup.erase(log, cleanLength, WalWriteGroup.writtenEnd(bytes, cleanLength),
                "Could not erase the torn end of the write-ahead log " + log.getPath())) {
            throw new IOException("The torn end of the write-ahead log " + log.getPath() + " could not be erased, so"
                    + " no write is appended to it");
        }
        for (final var record : decoded.records()) {
            for (final var change : record.changes()) {
                if (change.target() != WalTarget.PK_WRITE) {
                    touchedPages.add(change.page());
                }
            }
        }
        recordsSinceCheckpoint = decoded.framesSinceCheckpoint();
        groupBaseLogged = decoded.groupBase() != WalCodec.NO_GROUP_BASE;
        pendingLogged = decoded.pendingLogged();
        pendingFrameBytes = pendingLogged ? WalCodec.encodePending(0L, decoded.pendingIds()).length : 0L;
        nextSeq = Math.max(nextSeq, decoded.lastSeq() + 1);
        knownLength = cleanLength;
        allocatedLength = bytes.length;
        seeded = true;
    }
}
