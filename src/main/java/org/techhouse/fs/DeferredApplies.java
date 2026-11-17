package org.techhouse.fs;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;

public final class DeferredApplies {
    private static final Set<String> collections = ConcurrentHashMap.newKeySet();
    private static volatile boolean any;
    private static volatile DeferredApplyListener listener = DeferredApplyListener.NONE;

    private DeferredApplies() {
    }

    public static boolean pending(String collectionId) {
        return any && collections.contains(collectionId);
    }

    static synchronized void flag(String dbName, String collName) {
        collections.add(idOf(dbName, collName));
        any = true;
    }

    static void flagIfUnapplied(FilePaths paths, String dbName, String collName) {
        final var state = WalLogState.known(paths.writeAheadLog(dbName, collName).getAbsolutePath());
        if (state != null && state.unapplied) {
            flag(dbName, collName);
        }
    }

    static void clear(String dbName, String collName) {
        if (any) {
            clearFlag(dbName, collName);
        }
    }

    private static synchronized void clearFlag(String dbName, String collName) {
        collections.remove(idOf(dbName, collName));
        any = !collections.isEmpty();
    }

    static void listenWith(DeferredApplyListener replacement) {
        listener = replacement == null ? DeferredApplyListener.NONE : replacement;
    }

    static void replay(FilePaths paths, String dbName, String collName, WalCodec.Decoded decoded) throws IOException {
        final var rows = WalRedo.writtenRows(dbName, collName, decoded.records());
        final var current = listener;
        final var generation = current.replaying(dbName, collName, rows);
        try {
            WalRedo.replay(paths, dbName, collName, decoded);
        } catch (IOException | RuntimeException failure) {
            current.notReplayed(dbName, collName, rows, generation);
            throw failure;
        }
        current.replayed(dbName, collName, rows, generation);
    }

    private static String idOf(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
