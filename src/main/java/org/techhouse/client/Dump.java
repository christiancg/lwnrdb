package org.techhouse.client;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class Dump {
    public static final int PAGE_SIZE = 1000;
    public static final int MAX_PAGE_SIZE = Globals.SNAPSHOT_MAX_PAGE_DOCUMENTS;
    public static final int BATCH_BYTES = 4 * 1024 * 1024;
    public static final long PAGE_TARGET_BYTES = 4L * 1024 * 1024;
    private static final String DONE = "done";
    private static final String LAST_ID = "lastId";
    private static final String NO_SCHEMA = "404-12";
    private static final String RESPONSE_BUDGET_EXHAUSTED = "503-17";
    private static final Set<String> SKIPPABLE_LISTINGS = Set.of("403-2", "403-6");
    private static final int SIZE_HINT = 256;
    private static final EJson eJson = IocContainer.get(EJson.class);

    private final Connection connection;
    private final ClientOptions options;
    private final OutputStream stdout;
    private final PrintStream err;
    private OutputStream sink;
    private JsonArray batch = new JsonArray();
    private long batchBytes;

    private record SnapshotPage(List<JsonObject> documents, String lastId, boolean done) {
    }

    public Dump(Connection connection, ClientOptions options, OutputStream stdout, PrintStream err) {
        this.connection = connection;
        this.options = options;
        this.stdout = stdout;
        this.err = err;
    }

    public int run() {
        final var scope = DumpScope.plan(connection, options, err);
        final var buffered = new BufferedOutputStream(stdout);
        try {
            sink = options.compress() ? new GZIPOutputStream(buffered) : buffered;
            for (final var database : scope.databases()) {
                dumpDatabase(scope, database);
            }
        } catch (IOException e) {
            throw writeFailure(e);
        } finally {
            finish(buffered);
        }
        return ClientException.OK;
    }

    private void finish(BufferedOutputStream buffered) {
        try {
            if (sink instanceof GZIPOutputStream gzip) {
                gzip.finish();
            }
            buffered.flush();
        } catch (IOException e) {
            throw writeFailure(e);
        }
    }

    private static ClientException writeFailure(IOException e) {
        return new ClientException("cannot write the dump: " + e, ClientException.FAILED, e);
    }

    private void dumpDatabase(DumpScope scope, DumpScope.DatabasePlan plan) throws IOException {
        final var database = plan.name();
        emit(Requests.createDatabase(database));
        final var dumped = plan.collections().isEmpty() ? List.<String>of() : dumpCollections(database, plan);
        for (final var collection : dumped) {
            dumpSchema(database, collection);
        }
        for (final var collection : dumped) {
            final var fields = scope.indexesOf(database, collection);
            if (!fields.isEmpty()) {
                emit(Requests.createIndexes(database, collection, fields));
            }
        }
        dumpDefinitions(plan);
    }

    private List<String> dumpCollections(String database, DumpScope.DatabasePlan plan) throws IOException {
        final var dumped = new ArrayList<String>();
        try (var snapshot = DumpSnapshot.open(connection, database, plan.collections())) {
            for (final var collection : plan.collections()) {
                if (snapshot.collections().contains(collection)) {
                    emit(Requests.createCollection(database, collection));
                    dumpDocuments(snapshot, database, collection);
                    dumped.add(collection);
                } else {
                    err.println("note: " + database + "." + collection
                            + " was not dumped: it no longer exists or cannot be read");
                }
            }
        }
        return dumped;
    }

    private void dumpDocuments(DumpSnapshot snapshot, String database, String collection) throws IOException {
        String afterId = null;
        var limit = PAGE_SIZE;
        var count = 0;
        while (true) {
            final var page = pageOf(snapshot, database, collection, afterId, limit);
            var pageBytes = 0L;
            for (final var document : page.documents()) {
                pageBytes += append(database, collection, document);
            }
            count += page.documents().size();
            if (page.done()) {
                break;
            }
            afterId = page.lastId();
            if (!page.documents().isEmpty()) {
                limit = nextPageSize(pageBytes, page.documents().size());
            }
        }
        flushBatch(database, collection);
        err.println(database + "." + collection + ": " + count + " documents");
    }

    private SnapshotPage pageOf(DumpSnapshot snapshot, String database, String collection, String afterId,
            int firstLimit) {
        var limit = firstLimit;
        var reply = pageReply(snapshot, database, collection, afterId, limit);
        while (reply == null || limit > 1 && RESPONSE_BUDGET_EXHAUSTED.equals(Connection.errorCodeOf(reply))) {
            limit = Math.max(1, limit / 2);
            reply = pageReply(snapshot, database, collection, afterId, limit);
        }
        if (!Connection.isOk(reply)) {
            throw snapshot.refused(collection, afterId, limit, reply);
        }
        final var done = JsonBoolean.TRUE.equals(reply.get(DONE));
        final var lastId = Connection.stringOf(reply, LAST_ID);
        if (!done && lastId == null) {
            throw DumpScope.unexpectedReply(LAST_ID);
        }
        return new SnapshotPage(DumpScope.objectsOf(reply, "results"), lastId, done);
    }

    private JsonObject pageReply(DumpSnapshot snapshot, String database, String collection, String afterId, int limit) {
        try {
            return snapshot.read(collection, afterId, limit);
        } catch (ClientException failure) {
            if (!Connection.isReplyTooLong(failure)) {
                throw failure;
            }
            if (limit == 1) {
                throw new ClientException("the document of " + database + "." + collection + " after "
                        + (afterId == null ? "the start" : "'" + afterId + "'")
                        + " is larger than the client's reply limit", ClientException.FAILED, failure);
            }
            return null;
        }
    }

    public static int nextPageSize(long pageBytes, int documents) {
        final var bytesPerDocument = Math.max(1L, pageBytes / Math.max(1, documents));
        return (int) Math.clamp(PAGE_TARGET_BYTES / bytesPerDocument, 1L, MAX_PAGE_SIZE);
    }

    private int append(String database, String collection, JsonObject document) throws IOException {
        final var size = eJson.toJsonBytes(document, SIZE_HINT).length;
        if (!batch.isEmpty() && batchBytes + size > BATCH_BYTES) {
            flushBatch(database, collection);
        }
        batch.add(document);
        batchBytes += size;
        return size;
    }

    private void flushBatch(String database, String collection) throws IOException {
        if (!batch.isEmpty()) {
            emit(Requests.bulkSave(database, collection, batch));
            batch = new JsonArray();
            batchBytes = 0;
        }
    }

    private void dumpSchema(String database, String collection) throws IOException {
        final var request = Requests.getSchema(database, collection);
        final var reply = connection.send(request);
        final var schema = reply.get("schema");
        if (Connection.isOk(reply) && schema != null && schema.isJsonObject()) {
            emit(Requests.saveSchema(database, collection, schema.asJsonObject()));
        } else if (Connection.isOk(reply)) {
            throw Connection.refused(request, reply);
        } else if (!NO_SCHEMA.equals(Connection.errorCodeOf(reply))) {
            throw Connection.refused(request, reply);
        }
    }

    private void dumpDefinitions(DumpScope.DatabasePlan plan) throws IOException {
        final var database = plan.name();
        final var procedures = listed(Requests.listProcedures(database, true), "procedures");
        if (procedures.isPresent()) {
            dumpProceduresAndTriggers(plan, procedures.get());
        } else {
            err.println("note: triggers of " + database + " were not dumped: their procedures could not be listed");
        }
        if (plan.whole()) {
            for (final var schedule : listedOrEmpty(Requests.listSchedules(database, true), "schedules")) {
                emit(Requests.saveScheduleFrom(database, schedule));
            }
        }
    }

    private void dumpProceduresAndTriggers(DumpScope.DatabasePlan plan, List<JsonObject> procedures)
            throws IOException {
        final var database = plan.name();
        final var triggers = new ArrayList<JsonObject>();
        for (final var trigger : listedOrEmpty(Requests.listTriggers(database), "triggers")) {
            if (plan.keepsTrigger(trigger)) {
                triggers.add(trigger);
            }
        }
        for (final var procedure : proceduresToKeep(plan, procedures, triggers)) {
            emit(Requests.saveProcedureFrom(database, procedure));
        }
        for (final var trigger : triggers) {
            emit(Requests.saveTriggerFrom(database, trigger));
        }
    }

    private static List<JsonObject> proceduresToKeep(DumpScope.DatabasePlan plan, List<JsonObject> procedures,
            List<JsonObject> triggers) {
        final var order = new ProcedureOrder(procedures);
        if (plan.whole()) {
            return order.order();
        }
        final var roots = new ArrayList<String>();
        for (final var trigger : triggers) {
            roots.add(Connection.stringOf(trigger, Requests.PROCEDURE_NAME));
        }
        final var needed = order.closureOf(roots);
        final var kept = new ArrayList<JsonObject>();
        for (final var procedure : order.order()) {
            if (needed.contains(Connection.stringOf(procedure, Requests.NAME))) {
                kept.add(procedure);
            }
        }
        return kept;
    }

    private List<JsonObject> listedOrEmpty(JsonObject request, String field) {
        return listed(request, field).orElse(List.of());
    }

    private Optional<List<JsonObject>> listed(JsonObject request, String field) {
        final var reply = connection.send(request);
        final var errorCode = Connection.errorCodeOf(reply);
        if (errorCode != null && SKIPPABLE_LISTINGS.contains(errorCode)) {
            err.println("note: " + field + " of " + Connection.stringOf(request, Requests.DATABASE)
                    + " were not dumped: " + Connection.stringOf(reply, Connection.MESSAGE));
            return Optional.empty();
        }
        if (!Connection.isOk(reply)) {
            throw Connection.refused(request, reply);
        }
        return Optional.of(DumpScope.objectsOf(reply, field));
    }

    private void emit(JsonObject request) throws IOException {
        sink.write(eJson.toJsonLine(request, SIZE_HINT, "\n"));
    }
}
