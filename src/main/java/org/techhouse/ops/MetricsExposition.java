package org.techhouse.ops;

import java.lang.management.ManagementFactory;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.InFlightRequests;
import org.techhouse.conn.RequestMetrics;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.fs.ForceMetrics;
import org.techhouse.ioc.IocContainer;
import org.techhouse.metrics.PrometheusText;

public final class MetricsExposition {
    private static final double MILLIS_PER_SECOND = 1000d;
    private static final String PREFIX = "lwnrdb_";
    private static final String OUTCOME = "outcome";
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private static final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private static final RequestMetrics requestMetrics = IocContainer.get(RequestMetrics.class);
    private static final ResponseBudget responseBudget = IocContainer.get(ResponseBudget.class);
    private static final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ScriptLoad scriptLoad = IocContainer.get(ScriptLoad.class);
    private static final ScriptAdmission scriptAdmission = IocContainer.get(ScriptAdmission.class);
    private static final Configuration configuration = Configuration.getInstance();

    private MetricsExposition() {
    }

    public static String render() {
        final var text = new PrometheusText();
        renderBuild(text);
        renderRequests(text);
        renderConnections(text);
        renderMemory(text);
        renderQueues(text);
        renderScripting(text);
        renderStorage(text);
        return text.text();
    }

    private static String name(String suffix) {
        return PREFIX + suffix;
    }

    private static void gauge(PrometheusText text, String suffix, String help, double value) {
        text.family(name(suffix), PrometheusText.GAUGE, help).sample(name(suffix), value);
    }

    private static void counter(PrometheusText text, String suffix, String help, double value) {
        text.family(name(suffix), PrometheusText.COUNTER, help).sample(name(suffix), value);
    }

    private static void renderBuild(PrometheusText text) {
        text.family(name("build_info"), PrometheusText.GAUGE,
                "The release, wire protocol version and storage format version of this server.")
                .sample(name("build_info"), 1, "version", ServerVersion.current(), "protocol_version",
                        Integer.toString(Globals.PROTOCOL_VERSION), "storage_format_version",
                        Integer.toString(Globals.STORAGE_FORMAT_VERSION));
        gauge(text, "uptime_seconds", "Seconds since the JVM started.",
                ManagementFactory.getRuntimeMXBean().getUptime() / MILLIS_PER_SECOND);
    }

    private static void renderRequests(PrometheusText text) {
        final var requests = name("requests_total");
        text.family(requests, PrometheusText.COUNTER, "Requests answered, by operation, status and error code.");
        requestMetrics.forEachCount((operation, status, code, count) -> text.sample(requests, count, "operation",
                operation, "status", status, "code", code));
        final var durations = name("request_duration_seconds");
        text.family(durations, PrometheusText.HISTOGRAM,
                "Seconds from a parsed request to its queued response, by operation; the socket write is not included.");
        requestMetrics.forEachDuration(
                (operation, histogram) -> text.histogram(durations, histogram, "operation", operation));
    }

    private static void renderConnections(PrometheusText text) {
        gauge(text, "connections", "Open client connections.", clientTracker.connectedClients());
        final var refused = name("connections_refused_total");
        text.family(refused, PrometheusText.COUNTER, "Client connections refused at accept, by reason.");
        for (final var reason : RequestMetrics.RefusalReason.values()) {
            text.sample(refused, requestMetrics.refusedCount(reason), "reason", reason.label());
        }
        gauge(text, "in_flight_requests", "Requests being served right now.", inFlightRequests.current());
        gauge(text, "unsent_response_bytes", "Bytes of responses encoded but not yet written to their clients.",
                responseBudget.unsentBytes());
        gauge(text, "unsent_response_limit_bytes", "The maxUnsentResponseBytes budget in bytes.",
                responseBudget.limit());
    }

    private static void renderMemory(PrometheusText text) {
        gauge(text, "cache_bytes", "Modelled heap bytes the user cache holds.", cache.userCacheFootprintBytes());
        gauge(text, "cache_limit_bytes", "The maxMemory budget of the user cache in bytes (+Inf when unlimited).",
                cacheLimitBytes());
        final var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        gauge(text, "heap_used_bytes", "JVM heap in use.", heap.getUsed());
        gauge(text, "heap_max_bytes", "JVM maximum heap.", heap.getMax());
        final var collections = name("gc_collections_total");
        text.family(collections, PrometheusText.COUNTER, "Garbage collections, by collector.");
        final var collectors = ManagementFactory.getGarbageCollectorMXBeans();
        for (final var collector : collectors) {
            text.sample(collections, Math.max(0L, collector.getCollectionCount()), "gc", collector.getName());
        }
        final var seconds = name("gc_seconds_total");
        text.family(seconds, PrometheusText.COUNTER, "Seconds spent in garbage collection, by collector.");
        for (final var collector : collectors) {
            text.sample(seconds, Math.max(0L, collector.getCollectionTime()) / MILLIS_PER_SECOND, "gc",
                    collector.getName());
        }
    }

    private static double cacheLimitBytes() {
        if (configuration.isCachingDisabled()) {
            return 0;
        }
        return configuration.isCacheUnlimited() ? Double.POSITIVE_INFINITY : configuration.getMaxMemoryBytes();
    }

    private static void renderQueues(PrometheusText text) {
        final var background = name("background_queued");
        text.family(background, PrometheusText.GAUGE, "Background events waiting, by queue.")
                .sample(background, backgroundTaskManager.gatedPending(), "queue", "index")
                .sample(background, backgroundTaskManager.maintenancePending(), "queue", "maintenance")
                .sample(background, backgroundTaskManager.queuedHistory(), "queue", "history");
        final var triggers = name("triggers_total");
        text.family(triggers, PrometheusText.COUNTER, "Trigger runs, by outcome.")
                .sample(triggers, triggerExecutor.getFired(), OUTCOME, "fired")
                .sample(triggers, triggerExecutor.getFailed(), OUTCOME, "failed")
                .sample(triggers, triggerExecutor.getDropped(), OUTCOME, "dropped")
                .sample(triggers, triggerExecutor.getRetried(), OUTCOME, "retried")
                .sample(triggers, triggerExecutor.getDeadLettered(), OUTCOME, "dead_lettered");
        gauge(text, "trigger_queue", "Trigger runs waiting to be dispatched.", triggerExecutor.getQueued());
        final var schedules = name("schedules_total");
        text.family(schedules, PrometheusText.COUNTER, "Scheduled procedure runs, by outcome.")
                .sample(schedules, scheduleExecutor.getFired(), OUTCOME, "fired")
                .sample(schedules, scheduleExecutor.getFailed(), OUTCOME, "failed")
                .sample(schedules, scheduleExecutor.getSkipped(), OUTCOME, "skipped")
                .sample(schedules, scheduleExecutor.getDropped(), OUTCOME, "dropped");
        gauge(text, "schedule_queue", "Scheduled runs waiting to be dispatched.", scheduleExecutor.getQueued());
    }

    private static void renderScripting(PrometheusText text) {
        gauge(text, "scripts_running", "Scripts running on this node.", scriptLoad.current());
        counter(text, "scripts_rejected_total", "Scripts refused because every script slot was taken.",
                scriptAdmission.getRejected());
        gauge(text, "in_doubt_transactions", "Distributed transactions prepared on this node and not yet resolved.",
                Tx2pcLog.preparedDtxIds().size());
    }

    private static void renderStorage(PrometheusText text) {
        counter(text, "fsync_total", "Files and folders forced to the device.", ForceMetrics.count());
        counter(text, "fsync_failures_total", "Forces that failed.", ForceMetrics.failures());
        final var fsync = name("fsync_duration_seconds");
        text.family(fsync, PrometheusText.HISTOGRAM, "Seconds one force to the device took.").histogram(fsync,
                ForceMetrics.durations());
        final var stored = StoredTotals.ofEveryUserDatabase();
        gauge(text, "databases", "User databases.", stored.databases());
        gauge(text, "collections", "Collections across every user database.", stored.collections());
        gauge(text, "documents", "Documents across every user database, from the page rows.", stored.entries());
        gauge(text, "live_bytes", "Live document bytes across every user database, from the page rows.",
                stored.sizeBytes());
    }
}
