package org.techhouse.ops;

import static org.techhouse.simplejs.host.ScriptErrorNames.TIMED_OUT_MESSAGE;
import static org.techhouse.simplejs.host.ScriptErrorNames.TIMEOUT;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.techhouse.analyze.AnalyzeContext;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.operators.CustomOperator;
import org.techhouse.simplejs.ScriptCallable;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.host.PipelineHostBindings;
import org.techhouse.simplejs.host.ResourceLimits;

public final class PipelineContext implements ReadObserver, AutoCloseable {
    private static final int CHECK_MASK = 0xFF;
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final CompiledScriptCache compiledScriptCache = IocContainer.get(CompiledScriptCache.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Configuration configuration = Configuration.getInstance();

    private final Map<String, ScriptCallable> callables = new HashMap<>();
    private final Map<CustomOperator, BiPredicate<JsonObject, String>> customTesters = new IdentityHashMap<>();
    private final long scriptDeadline = System.currentTimeMillis() + configuration.getAggregationScriptTimeoutMs();
    private final boolean scriptsAllowed;
    private final boolean timed;
    private final long deadlineNanos;
    private final long maxResultBytes;
    private final AnalyzeContext analyze;
    private int checks;
    private long resultBytes;

    private PipelineContext(long timeoutMs, long maxResultBytes, AnalyzeContext analyze, boolean scriptsAllowed) {
        this.timed = timeoutMs > 0;
        this.deadlineNanos = timed ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0L;
        this.maxResultBytes = maxResultBytes;
        this.analyze = analyze;
        this.scriptsAllowed = scriptsAllowed;
    }

    public static PipelineContext of(long timeoutMs, long maxResultBytes, AnalyzeContext analyze) {
        return new PipelineContext(timeoutMs, maxResultBytes, analyze, true);
    }

    public static PipelineContext fromConfiguration(AnalyzeContext analyze) {
        return of(configuration.longValue(ConfigKey.AGGREGATE_TIMEOUT_MS), configuredMaxResultBytes(), analyze);
    }

    public static PipelineContext withoutScripts() {
        return new PipelineContext(0L, Long.MAX_VALUE, null, false);
    }

    public static long configuredMaxResultBytes() {
        return configuration.sizeValue(ConfigKey.AGGREGATE_MAX_RESULT_BYTES);
    }

    public <T> Stream<T> watch(Stream<T> stream) {
        if (!timed || stream == null) {
            return stream;
        }
        return stream.peek(_ -> checkDeadline());
    }

    private void checkDeadline() {
        if ((++checks & CHECK_MASK) == 0 && System.nanoTime() > deadlineNanos) {
            throw new AggregateLimitException(ErrorCode.AGGREGATE_TIMEOUT);
        }
    }

    public Stream<JsonObject> source(Stream<JsonObject> resultStream, String dbName, String collName)
            throws IOException {
        if (resultStream != null) {
            return resultStream;
        }
        return scan(cache.streamCollection(dbName, collName)).map(DbEntry::getData);
    }

    public Stream<DbEntry> scan(Stream<DbEntry> entries) {
        return watch(analyze == null ? entries : entries.peek(_ -> analyze.addScanned(1)));
    }

    public void chargeResult(long bytes) {
        resultBytes += bytes;
        if (resultBytes > maxResultBytes) {
            throw new AggregateLimitException(ErrorCode.AGGREGATE_RESULT_TOO_LARGE);
        }
    }

    public AnalyzeContext analyze() {
        return analyze;
    }

    @Override
    public void scanned(long count) {
        if (analyze != null) {
            analyze.addScanned(count);
        }
    }

    @Override
    public void indexUsed(String field, String lockId) {
        if (analyze != null) {
            analyze.addIndexUsed(field);
            analyze.addLock(lockId);
        }
    }

    public BiPredicate<JsonObject, String> customTesterFor(CustomOperator operator) {
        return customTesters.computeIfAbsent(operator, FilterOperatorHelper::getCustomTester);
    }

    public ScriptCallable callableFor(String source) {
        if (!scriptsAllowed) {
            throw new ScriptCallableException("InternalError", "No script context available for this pipeline");
        }
        final var existing = callables.get(source);
        if (existing != null) {
            return existing;
        }
        final var remaining = scriptDeadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new ScriptCallableException(TIMEOUT, TIMED_OUT_MESSAGE);
        }
        final var bindings = PipelineHostBindings.of(limits(remaining));
        final var cached = compiledScriptCache.get(source);
        final var opened = cached.failure() == null
                ? simpleJs.openCallable(cached.compiled(), bindings)
                : simpleJs.openCallable(source, bindings);
        callables.put(source, opened);
        return opened;
    }

    private ResourceLimits limits(long remainingMillis) {
        final var base = PipelineHostBindings.limitsFromConfiguration();
        return new ResourceLimits(base.instructionBudget(), remainingMillis, base.maxDepth(),
                base.reportUnhandledRejections(), base.fetchEnabled(), base.fetchHostAllowlist(),
                base.maxResponseBytes(), base.fetchTimeoutMillis(), base.strictScriptGoal(), base.textImportEnabled(),
                base.maxModuleDepth(), base.maxLogLines(), base.maxLogLineChars(), base.memoryBudget(),
                base.maxResultBytes(), base.cursorBatchSize(), base.cursorMaxBatchSize(), base.fetchMaxConcurrent(),
                base.maxArrayLength());
    }

    @Override
    public void close() {
        for (final var callable : callables.values()) {
            callable.close();
        }
        callables.clear();
        customTesters.clear();
    }
}
