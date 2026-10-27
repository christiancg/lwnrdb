package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.ResponseParser;

public class ScriptRunHistory {
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 3L;
    private static final int MAX_NOT_READY_RETRIES = 5;
    private static final long FIRST_RETRY_DELAY_MILLIS = 100L;

    private static final Logger logger = Logger.logFor(ScriptRunHistory.class);
    @SuppressWarnings("FieldMayBeFinal")
    private static BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);
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
    private static final AtomicReference<ScheduledExecutorService> sweeper = new AtomicReference<>();
    private static final AtomicBoolean queueFull = new AtomicBoolean();

    public static void record(ScriptRunRecord runRecord) {
        if (runRecord == null || !isEnabled() || !recordsKind(runRecord.kind())) {
            return;
        }
        final var cap = configuration.intValue(ConfigKey.SCRIPT_RUN_HISTORY_MAX_QUEUED);
        if (taskManager.queuedHistory() >= cap) {
            dropped.increment();
            if (queueFull.compareAndSet(false, true)) {
                logger.warning("The script run history queue holds " + cap + " rows (scriptRunHistoryMaxQueued);"
                        + " runs are not recorded until it drains");
            }
            return;
        }
        queueFull.set(false);
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
        write(runRecord, 0);
    }

    public static void write(ScriptRunRecord runRecord, int retries) {
        if (runRecord == null || !isEnabled()) {
            return;
        }
        try {
            final var dbName = runRecord.database();
            if (dbName == null || historyUnavailable(dbName)) {
                dropped.increment();
                return;
            }
            final var request = new SaveRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
            request.setObject(documentOf(runRecord));
            request.set_id(runRecord.runId());
            request.setBackgroundFollowOn(true);
            final var response = dispatch(request, runRecord.username());
            if (response.getStatus() != OperationStatus.OK) {
                if (isNotReady(response) && retryLater(runRecord, retries)) {
                    return;
                }
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

    public static void writeAll(List<ScriptRunHistoryEvent> events) {
        if (!isEnabled()) {
            return;
        }
        final var byRun = new LinkedHashMap<Object, ScriptRunHistoryEvent>();
        for (final var event : events) {
            final var runRecord = event.getRecord();
            if (runRecord != null) {
                byRun.put(runRecord.runId() == null ? event : runRecord.runId(), event);
            }
        }
        if (byRun.isEmpty()) {
            return;
        }
        final var batch = List.copyOf(byRun.values());
        if (batch.size() == 1) {
            write(batch.getFirst().getRecord(), batch.getFirst().getRetries());
            return;
        }
        try {
            final var dbName = batch.getFirst().getRecord().database();
            if (dbName == null || historyUnavailable(dbName)) {
                dropped.add(batch.size());
                return;
            }
            final var documents = new ArrayList<JsonObject>(batch.size());
            for (final var event : batch) {
                documents.add(documentOf(event.getRecord()));
            }
            final var request = new BulkSaveRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
            request.setObjects(documents);
            request.setBackgroundFollowOn(true);
            final var response = dispatch(request, null);
            if (response.getStatus() == OperationStatus.OK) {
                recorded.add(batch.size());
            } else if (isNotReady(response)) {
                for (final var event : batch) {
                    if (!retryLater(event.getRecord(), event.getRetries())) {
                        dropped.increment();
                    }
                }
            } else {
                for (final var event : batch) {
                    write(event.getRecord(), event.getRetries());
                }
            }
        } catch (Exception e) {
            dropped.add(batch.size());
            logger.warning("Failed to record " + batch.size() + " script runs in history: " + e.getMessage());
        }
    }

    private static boolean isNotReady(OperationResponse response) {
        return ErrorCode.COLLECTION_NOT_READY.getCode().equals(response.getErrorCode());
    }

    private static JsonObject documentOf(ScriptRunRecord runRecord) {
        final var document = toDocument(runRecord);
        if (runRecord.runId() != null) {
            document.addProperty(Globals.PK_FIELD, runRecord.runId());
        }
        return document;
    }

    public static void sweepOnce() {
        if (!isEnabled()) {
            return;
        }
        final var cutoff = System.currentTimeMillis() - Math.max(1L, configuration.getScriptRunHistoryRetentionMs());
        final var maxRows = configuration.longValue(ConfigKey.SCRIPT_RUN_HISTORY_MAX_ROWS);
        for (final var dbName : databasesWithHistory()) {
            if (!ownsHistoryOf(dbName)) {
                continue;
            }
            try {
                ScriptRunPrune.prune(dbName, cutoff, maxRows);
            } catch (Exception e) {
                logger.warning("Failed to prune the script run history of '" + dbName + "': " + e.getMessage());
            }
        }
    }

    private static boolean retryLater(ScriptRunRecord runRecord, int retries) {
        final var scheduler = sweeper.get();
        if (scheduler == null || retries >= MAX_NOT_READY_RETRIES) {
            return false;
        }
        try {
            scheduler.schedule(
                    () -> taskManager.submitBackgroundTask(new ScriptRunHistoryEvent(runRecord, retries + 1)),
                    FIRST_RETRY_DELAY_MILLIS << retries, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException stopping) {
            return false;
        }
    }

    private static boolean ownsHistoryOf(String dbName) {
        return !clusterConfig.isEnabled() || ownershipManager.isOwner(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME);
    }

    public void startSweep() {
        if (!isEnabled() || sweeper.get() != null) {
            return;
        }
        final var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final var thread = new Thread(runnable, "script-run-history-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        if (!sweeper.compareAndSet(null, scheduler)) {
            scheduler.shutdownNow();
            return;
        }
        final var interval = configuration.longValue(ConfigKey.SCRIPT_RUN_HISTORY_SWEEP_MS);
        scheduler.scheduleAtFixedRate(ScriptRunHistory::sweepQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stopSweep() {
        final var running = sweeper.getAndSet(null);
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
        queueFull.set(false);
    }

    public static void sweepQuietly() {
        try {
            sweepOnce();
        } catch (Throwable failure) {
            logger.warning("The script run history sweep failed: " + failure.getMessage());
        }
    }

    private static Set<String> databasesWithHistory() {
        final var names = new LinkedHashSet<>(knownDatabases);
        names.addAll(cache.getUserDatabaseNames());
        names.remove(Globals.ADMIN_DB_NAME);
        return names;
    }

    private static boolean historyUnavailable(String dbName) {
        if (cache.getAdminDbEntry(dbName) == null) {
            knownDatabases.remove(dbName);
            return true;
        }
        if (cache.getAdminCollectionEntry(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME) != null) {
            knownDatabases.add(dbName);
            return false;
        }
        knownDatabases.remove(dbName);
        final var response = dispatch(new CreateCollectionRequest(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME), null);
        if (response.getStatus() != OperationStatus.OK) {
            logger.warning(
                    "Could not create the script run history collection in '" + dbName + "': " + response.getMessage());
            return true;
        }
        knownDatabases.add(dbName);
        return false;
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
        document.addProperty("errorName", JsonCustom.asPlainText(runRecord.errorName()));
        document.addProperty("errorMessage", JsonCustom.asPlainText(clip(runRecord.errorMessage())));
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
            array.add(new JsonString(JsonCustom.asPlainText(value)));
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
