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
    long nextSeq = 1L;
    volatile long knownLength;
    int recordsSinceCheckpoint;
    boolean unapplied;
    boolean groupBaseLogged;
    boolean pendingLogged;
    WalWriteGroup group;
    volatile long lastWriteMillis;

    private WalLogState(String dbName, String collName, String path) {
        this.dbName = dbName;
        this.collName = collName;
        this.path = path;
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

    static WalLogState knownOrRead(File log, String path, String dbName, String collName) throws IOException {
        final var known = states.get(path);
        return known != null ? known : of(log, path, dbName, collName);
    }

    static WalLogState of(File log, String path, String dbName, String collName) throws IOException {
        final var state = states.computeIfAbsent(path, _ -> new WalLogState(dbName, collName, path));
        if (log.length() != state.knownLength) {
            state.reseed(log);
        }
        return state;
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
        final var decoded = log.exists() ? WalCodec.decodeAll(Files.readAllBytes(log.toPath())) : null;
        if (decoded == null || decoded.checkpoint() == null) {
            recordsSinceCheckpoint = 0;
            pendingLogged = false;
            knownLength = 0L;
            return;
        }
        if (decoded.cleanLength() != log.length()) {
            FileLocks.truncateTo(log, decoded.cleanLength(), "Could not cut the torn end of the write-ahead log "
                    + log.getPath() + "; the next startup cuts it");
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
        nextSeq = Math.max(nextSeq, decoded.lastSeq() + 1);
        knownLength = log.length();
    }
}
