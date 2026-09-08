package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ResponseParser;

public class ScriptRunHistory {
    private static final long SWEEP_INTERVAL_SECONDS = 3600L;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;

    private static final Logger logger = Logger.logFor(ScriptRunHistory.class);
    private static final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final OperationProcessor operationProcessor = IocContainer.get(OperationProcessor.class);
    private static final ClusterRouter clusterRouter = IocContainer.get(ClusterRouter.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);
    private static final Configuration configuration = Configuration.getInstance();

    private static final Set<String> knownDatabases = ConcurrentHashMap.newKeySet();
    private static final LongAdder recorded = new LongAdder();
    private static final LongAdder dropped = new LongAdder();

    private ScheduledExecutorService sweeper;

    public static void record(ScriptRunRecord runRecord) {
        if (runRecord == null || !isEnabled() || !recordsKind(runRecord.kind())) {
            return;
        }
        taskManager.submitBackgroundTask(new ScriptRunHistoryEvent(runRecord));
    }

    public static boolean isEnabled() {
        return configuration.isScriptRunHistoryEnabled();
    }

    public static boolean recordsKind(ScriptRunKind kind) {
        final var configured = configuration.getScriptRunHistoryKinds();
        if (kind == null || configured == null || configured.isBlank()) {
            return false;
        }
        for (final var name : configured.split(",")) {
            if (name.trim().equalsIgnoreCase(kind.name())) {
                return true;
            }
        }
        return false;
    }

    public static void write(ScriptRunRecord runRecord) {
        if (runRecord == null || !isEnabled()) {
            return;
        }
        try {
            final var dbName = runRecord.database();
            if (dbName == null || !ensureCollection(dbName)) {
                dropped.increment();
                return;
            }
            final var document = toDocument(runRecord);
            if (runRecord.runId() != null) {
                document.addProperty(Globals.PK_FIELD, runRecord.runId());
            }
            final var request = new SaveRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
            request.setObject(document);
            request.set_id(runRecord.runId());
            final var response = dispatch(request, runRecord.username());
            if (response.getStatus() != OperationStatus.OK) {
                dropped.increment();
                logger.warning("Could not record the run of " + runRecord.kind() + " '" + runRecord.name() + "' in "
                        + dbName + ": " + response.getMessage());
                return;
            }
            recorded.increment();
        } catch (Exception e) {
            dropped.increment();
            logger.warning("Failed to record a script run in history: " + e.getMessage());
        }
    }

    public static void sweepOnce() {
        if (!isEnabled()) {
            return;
        }
        final var cutoff = System.currentTimeMillis() - Math.max(1L, configuration.getScriptRunHistoryRetentionMs());
        for (final var dbName : databasesWithHistory()) {
            if (!ownsHistoryOf(dbName)) {
                continue;
            }
            try {
                pruneDatabase(dbName, cutoff);
            } catch (Exception e) {
                logger.warning("Failed to prune the script run history of '" + dbName + "': " + e.getMessage());
            }
        }
    }

    private static boolean ownsHistoryOf(String dbName) {
        return !clusterConfig.isEnabled() || ownershipManager.isOwner(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
    }

    public void startSweep() {
        if (!isEnabled() || sweeper != null) {
            return;
        }
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "script-run-history-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleAtFixedRate(ScriptRunHistory::sweepQuietly, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    public void stopSweep() {
        final var running = sweeper;
        sweeper = null;
        if (running == null) {
            return;
        }
        running.shutdownNow();
        try {
            if (!running.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warning(
                        "The script run history sweeper did not terminate within the timeout;" + " abandoning it");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static long getRecorded() {
        return recorded.sum();
    }

    public static long getDropped() {
        return dropped.sum();
    }

    public static void reset() {
        knownDatabases.clear();
        recorded.reset();
        dropped.reset();
    }

    public static void sweepQuietly() {
        try {
            sweepOnce();
        } catch (Exception e) {
            logger.warning("The script run history sweep failed: " + e.getMessage());
        }
    }

    private static Set<String> databasesWithHistory() {
        final var names = new LinkedHashSet<>(knownDatabases);
        names.addAll(cache.getUserDatabaseNames());
        names.remove(Globals.ADMIN_DB_NAME);
        return names;
    }

    private static void pruneDatabase(String dbName, long cutoff) throws Exception {
        if (cache.getAdminCollectionEntry(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME) == null) {
            return;
        }
        final var stale = new ArrayList<String>();
        try (var entries = cache.streamCollection(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME)) {
            entries.forEach(entry -> {
                final var data = entry.getData();
                if (data != null && data.has("startedAt")
                        && data.get("startedAt").asJsonNumber().getValue().longValue() < cutoff) {
                    stale.add(entry.get_id());
                }
            });
        }
        for (final var id : stale) {
            final var request = new DeleteRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
            request.set_id(id);
            dispatch(request, null);
        }
        if (!stale.isEmpty()) {
            logger.info("Pruned " + stale.size() + " script run history row(s) from '" + dbName + "'");
        }
    }

    private static boolean ensureCollection(String dbName) {
        if (knownDatabases.contains(dbName)) {
            return true;
        }
        if (cache.getAdminDbEntry(dbName) == null) {
            return false;
        }
        if (cache.getAdminCollectionEntry(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME) != null) {
            knownDatabases.add(dbName);
            return true;
        }
        final var response = dispatch(new CreateCollectionRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME), null);
        if (response.getStatus() != OperationStatus.OK) {
            logger.warning(
                    "Could not create the script run history collection in '" + dbName + "': " + response.getMessage());
            return false;
        }
        knownDatabases.add(dbName);
        return true;
    }

    private static OperationResponse dispatch(OperationRequest request, String actingUser) {
        final var clientId = clientTracker.registerForwardedClient(actingUser == null ? "" : actingUser);
        try {
            if (clusterConfig.isEnabled()) {
                final var forwarded = clusterRouter.forward(request, eJson.toJson(request), false, actingUser,
                        clientId);
                if (forwarded != null) {
                    return ResponseParser.parseResponse(forwarded);
                }
            }
            return operationProcessor.processMessage(request, clientId);
        } finally {
            clientTracker.removeById(clientId);
        }
    }

    private static JsonObject toDocument(ScriptRunRecord runRecord) {
        final var document = new JsonObject();
        document.addProperty("runId", runRecord.runId());
        document.addProperty("kind", runRecord.kind() == null ? null : runRecord.kind().name());
        document.addProperty("name", runRecord.name());
        document.addProperty("procedure", runRecord.procedure());
        document.addProperty("collection", runRecord.collection());
        document.addProperty("event", runRecord.event());
        document.addProperty("username", runRecord.username());
        document.addProperty("actingUser", runRecord.actingUser());
        document.addProperty("node", nodeAddress());
        document.addProperty("startedAt", runRecord.startedAt());
        document.addProperty("durationMs", runRecord.durationMs());
        document.addProperty("attempt", runRecord.attempt());
        document.addProperty("outcome", runRecord.outcome());
        document.addProperty("errorName", runRecord.errorName());
        document.addProperty("errorMessage", clip(runRecord.errorMessage()));
        document.add("stack", strings(runRecord.stack()));
        document.add("metrics", runRecord.metrics().toJson());
        document.add("logs",
                configuration.isScriptRunHistoryIncludeLogs() ? strings(runRecord.logs()) : new JsonArray());
        document.addProperty("logsTruncated", runRecord.logsTruncated());
        return document;
    }

    private static JsonArray strings(Iterable<String> values) {
        final var array = new JsonArray();
        for (final var value : values) {
            array.add(new JsonString(value));
        }
        return array;
    }

    private static String clip(String message) {
        final var max = Math.max(1, configuration.getScriptRunHistoryMaxErrorChars());
        if (message == null || message.length() <= max) {
            return message;
        }
        return message.substring(0, max) + "…";
    }

    private static String nodeAddress() {
        final var self = membershipService.getSelf();
        return self == null ? Globals.STANDALONE_NODE_ID : self.address().toString();
    }
}
