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
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public final class Dump {
    public static final int PAGE_SIZE = 1000;
    public static final int BATCH_BYTES = 4 * 1024 * 1024;
    private static final String NO_RESULTS = "404-3";
    private static final String NO_SCHEMA = "404-12";
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
        for (final var collection : plan.collections()) {
            emit(Requests.createCollection(database, collection));
            dumpDocuments(database, collection);
        }
        for (final var collection : plan.collections()) {
            dumpSchema(database, collection);
        }
        for (final var collection : plan.collections()) {
            for (final var field : scope.indexesOf(database, collection)) {
                emit(Requests.createIndex(database, collection, field));
            }
        }
        dumpDefinitions(plan);
    }

    private void dumpDocuments(String database, String collection) throws IOException {
        var skip = 0;
        while (true) {
            final var request = Requests.aggregatePage(database, collection, skip, PAGE_SIZE);
            final var reply = connection.send(request);
            if (NO_RESULTS.equals(Connection.errorCodeOf(reply))) {
                break;
            }
            if (!Connection.isOk(reply)) {
                throw Connection.refused(request, reply);
            }
            final var documents = DumpScope.objectsOf(reply, "results");
            if (documents.isEmpty()) {
                break;
            }
            for (final var document : documents) {
                append(database, collection, document);
            }
            skip += documents.size();
        }
        flushBatch(database, collection);
        err.println(database + "." + collection + ": " + skip + " documents");
    }

    private void append(String database, String collection, JsonObject document) throws IOException {
        final var size = eJson.toJsonBytes(document, SIZE_HINT).length;
        if (!batch.isEmpty() && batchBytes + size > BATCH_BYTES) {
            flushBatch(database, collection);
        }
        batch.add(document);
        batchBytes += size;
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
        if (Connection.isOk(reply)) {
            emit(Requests.saveSchema(database, collection, reply.get("schema").asJsonObject()));
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
