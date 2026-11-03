package org.techhouse.fs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

final class WalCheckpointRequests {
    private static final BooleanSupplier NOBODY_LISTENS = () -> false;
    private static final Map<String, Request> requested = new ConcurrentHashMap<>();
    private static volatile BooleanSupplier listener = NOBODY_LISTENS;

    record Request(String dbName, String collName) {
    }

    private WalCheckpointRequests() {
    }

    static void listenWith(BooleanSupplier onFirstRequest) {
        listener = onFirstRequest == null ? NOBODY_LISTENS : onFirstRequest;
    }

    static void request(String logPath, String dbName, String collName) {
        if (requested.putIfAbsent(logPath, new Request(dbName, collName)) == null && !listener.getAsBoolean()) {
            requested.remove(logPath);
        }
    }

    static List<Request> takeAll() {
        final var taken = new ArrayList<Request>(requested.size());
        for (final var path : List.copyOf(requested.keySet())) {
            final var request = requested.remove(path);
            if (request != null) {
                taken.add(request);
            }
        }
        return taken;
    }

    static boolean isRequested(String logPath) {
        return requested.containsKey(logPath);
    }

    static void forgetUnder(File folder) {
        final var prefix = folder.getAbsolutePath() + File.separator;
        requested.keySet().removeIf(path -> path.startsWith(prefix));
    }
}
