package org.techhouse.client;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.techhouse.ejson.elements.JsonObject;

final class DumpSnapshot implements AutoCloseable {
    static final int OPEN_ATTEMPTS = 5;
    static final Duration OPEN_BACKOFF = Duration.ofMillis(100);
    private static final String LOCK_TIMEOUT = "409-5";
    private static final Set<String> LOST = Set.of("404-13", "409-17");

    private final Connection connection;
    private final String database;
    private final String id;
    private final List<String> collections;

    private DumpSnapshot(Connection connection, String database, String id, List<String> collections) {
        this.connection = connection;
        this.database = database;
        this.id = id;
        this.collections = collections;
    }

    static DumpSnapshot open(Connection connection, String database, List<String> collections) {
        final var request = Requests.startSnapshot(database, collections);
        var reply = connection.send(request);
        var backoff = OPEN_BACKOFF;
        for (var attempt = 1; attempt < OPEN_ATTEMPTS
                && LOCK_TIMEOUT.equals(Connection.errorCodeOf(reply)); attempt++) {
            pause(backoff);
            backoff = backoff.multipliedBy(2);
            reply = connection.send(request);
        }
        if (!Connection.isOk(reply)) {
            throw Connection.refused(request, reply);
        }
        final var id = Connection.stringOf(reply, Requests.SNAPSHOT_ID);
        if (id == null) {
            throw DumpScope.unexpectedReply(Requests.SNAPSHOT_ID);
        }
        return new DumpSnapshot(connection, database, id, DumpScope.stringsOf(reply, Requests.COLLECTION_NAMES));
    }

    List<String> collections() {
        return collections;
    }

    JsonObject read(String collection, String afterId, int limit) {
        final var request = Requests.readSnapshot(database, collection, id, afterId, limit);
        final var reply = connection.send(request);
        final var errorCode = Connection.errorCodeOf(reply);
        if (errorCode != null && LOST.contains(errorCode)) {
            throw new ClientException("the snapshot of " + database + " was lost ("
                    + Connection.stringOf(reply, Connection.MESSAGE) + "); the dump is incomplete",
                    ClientException.FAILED);
        }
        return reply;
    }

    ClientException refused(String collection, String afterId, int limit, JsonObject reply) {
        return Connection.refused(Requests.readSnapshot(database, collection, id, afterId, limit), reply);
    }

    @Override
    public void close() {
        try {
            connection.send(Requests.releaseSnapshot(database, id));
        } catch (ClientException ignored) {
        }
    }

    private static void pause(Duration backoff) {
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("interrupted while opening a snapshot", ClientException.FAILED, e);
        }
    }
}
