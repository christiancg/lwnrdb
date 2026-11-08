package org.techhouse.unit.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.IndexLogChange;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

@SuppressWarnings("NewClassNamingConvention")
public class RefusedFoldStallProbe {
    private static final String DB = TestGlobals.DB;
    private static final String COLL = TestGlobals.COLL;
    private static final String FIELD = "tag";
    private static final long INDEX_BYTES = 50L * 1024 * 1024;
    private static final int VALUE_PAD = 392;
    private static final int ROUNDS = 3;
    private static final long BASELINE_WINDOW_MS = 3_000L;
    private static final long TAIL_WINDOW_MS = 500L;
    private static final int LOG_CHUNK_CHANGES = 2_000;
    private static final long LOG_MARGIN_BYTES = 1024;

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final BackgroundTaskManager taskManager = mock(BackgroundTaskManager.class);
    private final ConcurrentLinkedQueue<Event> queued = new ConcurrentLinkedQueue<>();
    private BackgroundTaskManager savedTaskManager;
    private Long savedMaxMemory;
    private String padding;
    private final AtomicInteger indexLines = new AtomicInteger();
    private final AtomicInteger logIds = new AtomicInteger();

    private record Sample(long startNanos, long latencyNanos) {
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        savedTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        doAnswer(invocation -> queued.add(invocation.getArgument(0))).when(taskManager)
                .submitBackgroundTask(any(Event.class));
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", taskManager);
        IndexHelper.createIndex(DB, COLL, FIELD);
        cache.getAdminCollectionEntry(DB, COLL).setIndexes(Set.of(FIELD));
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_DISABLED);
        final var pad = new char[VALUE_PAD];
        Arrays.fill(pad, 'p');
        padding = new String(pad);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", savedTaskManager);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Path indexPath() {
        return Path.of(TestGlobals.PATH, DB, COLL, COLL + "-" + FIELD + "-String.idx");
    }

    private Path logPath() {
        return Path.of(indexPath() + Globals.INDEX_LOG_SUFFIX);
    }

    private String valueOf(int i) {
        return String.format(Locale.ROOT, "k%07d", i) + padding;
    }

    private void writeLargeIndex() throws Exception {
        final var path = indexPath();
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            long written = 0;
            var i = 0;
            while (written < INDEX_BYTES) {
                final var line = valueOf(i) + Globals.ID_SEPARATOR + String.format(Locale.ROOT, "i%07d", i);
                out.write(line);
                out.write('\n');
                written += line.length() + 1L;
                i++;
            }
            indexLines.set(i);
        }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private void fillLogBelowThreshold() throws Exception {
        final var threshold = Math.max(Files.size(indexPath()) / Globals.REFUSED_INDEX_FOLD_DIVISOR,
                Globals.FIELD_INDEX_LOG_MIN_BYTES);
        while (true) {
            final var logBytes = Files.exists(logPath()) ? Files.size(logPath()) : 0L;
            final var remaining = threshold - LOG_MARGIN_BYTES - logBytes;
            if (remaining <= 0) {
                return;
            }
            final var changes = new ArrayList<IndexLogChange>(LOG_CHUNK_CHANGES);
            var bytes = 0L;
            while (changes.size() < LOG_CHUNK_CHANGES && bytes < remaining) {
                final var key = FieldIndexEntry.fileKeyOf(valueOf(logIds.get() % indexLines.get()));
                final var id = String.format(Locale.ROOT, "x%07d", logIds.getAndIncrement());
                changes.add(new IndexLogChange(key, List.of(id), List.of()));
                bytes += key.length() + id.length() + 4L;
            }
            fs.appendIndexLogOnly(DB, COLL, FIELD, "String", changes, null);
        }
    }

    private void save(String id, String value) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty(FIELD, value);
        final var request = new SaveRequest(DB, COLL);
        request.setObject(object);
        locks.lock(DB, COLL);
        try {
            SaveOperationHelper.executeSave(request);
        } finally {
            locks.release(DB, COLL);
        }
    }

    private Thread startWriter(String prefix, AtomicBoolean running, List<Sample> samples) {
        return Thread.ofPlatform().start(() -> {
            var i = 0;
            while (running.get()) {
                final var started = System.nanoTime();
                try {
                    save(prefix + i, valueOf(i % 1000));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                samples.add(new Sample(started, System.nanoTime() - started));
                i++;
            }
        });
    }

    private static List<Long> within(List<Sample> samples, long from, long to) {
        final var latencies = new ArrayList<Long>();
        for (final var sample : samples) {
            if (sample.startNanos() >= from && sample.startNanos() <= to) {
                latencies.add(sample.latencyNanos());
            }
        }
        latencies.sort(null);
        return latencies;
    }

    private static double percentileMs(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        final var rank = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.clamp(rank, 0, sorted.size() - 1)) / 1_000_000.0;
    }

    private void drainQueuedIntoAFold() throws Exception {
        final var events = new ArrayList<Event>();
        Event event;
        while ((event = queued.poll()) != null) {
            events.add(event);
        }
        EventProcessorHelper.processBatch(events);
    }

    @Test
    public void measureWriterLatencyWhileARefusedTypeFolds() throws Exception {
        writeLargeIndex();
        final var baseline = new ArrayList<Long>();
        final var duringFold = new ArrayList<Long>();
        final var foldMillis = new ArrayList<Long>();
        for (var round = 0; round < ROUNDS; round++) {
            fillLogBelowThreshold();
            queued.clear();
            final var logBefore = Files.size(logPath());
            final var samples = new ArrayList<Sample>();
            final var running = new AtomicBoolean(true);
            final var writer = startWriter("w" + round + "_", running, samples);
            final var baselineFrom = System.nanoTime();
            Thread.sleep(BASELINE_WINDOW_MS);
            final var baselineTo = System.nanoTime();
            queued.clear();
            Thread.sleep(50L);
            final var foldFrom = System.nanoTime();
            drainQueuedIntoAFold();
            final var foldTo = System.nanoTime();
            Thread.sleep(TAIL_WINDOW_MS);
            running.set(false);
            writer.join();
            final var folded = !Files.exists(logPath()) || Files.size(logPath()) < logBefore;
            assertTrue(folded, "round " + round + " never folded the log");
            baseline.addAll(within(samples, baselineFrom, baselineTo));
            duringFold.addAll(within(samples, foldFrom, foldTo));
            foldMillis.add((foldTo - foldFrom) / 1_000_000L);
        }
        baseline.sort(null);
        duringFold.sort(null);
        final var baseP99 = percentileMs(baseline, 0.99);
        final var foldP99 = percentileMs(duringFold, 0.99);
        System.out.printf(Locale.ROOT,
                "RefusedFoldStallProbe: index %d MB, %d lines; folds %s ms%n"
                        + "  baseline: n=%d p50=%.3f ms p99=%.3f ms max=%.3f ms%n"
                        + "  during fold: n=%d p50=%.3f ms p99=%.3f ms max=%.3f ms%n"
                        + "  p99 ratio %.2fx (criterion: within 2x -> %s)%n",
                Files.size(indexPath()) / (1024 * 1024), indexLines.get(), foldMillis, baseline.size(),
                percentileMs(baseline, 0.50), baseP99, percentileMs(baseline, 1.0), duringFold.size(),
                percentileMs(duringFold, 0.50), foldP99, percentileMs(duringFold, 1.0), foldP99 / baseP99,
                foldP99 <= 2 * baseP99 ? "PASS" : "FAIL");
    }
}
