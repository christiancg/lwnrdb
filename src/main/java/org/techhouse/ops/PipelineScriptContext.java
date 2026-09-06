package org.techhouse.ops;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.simplejs.ScriptCallable;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.host.PipelineHostBindings;
import org.techhouse.simplejs.host.ResourceLimits;

/**
 * The script state of one AGGREGATE request: a callable per distinct source, opened on first use and closed
 * when the pipeline is done. A source repeated across steps (or inside a conjunction) is opened once, and the
 * wall-clock deadline is the request's, not each callable's, so a pipeline carrying several different scripts
 * cannot outlive {@code aggregationScriptTimeoutMs} by opening more of them.
 */
public final class PipelineScriptContext implements AutoCloseable {
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final Configuration configuration = Configuration.getInstance();

    private final Map<String, ScriptCallable> callables = new HashMap<>();
    private final long deadline = System.currentTimeMillis() + configuration.getAggregationScriptTimeoutMs();

    public ScriptCallable callableFor(String source) {
        final var existing = callables.get(source);
        if (existing != null) {
            return existing;
        }
        final var remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new ScriptCallableException("ScriptTimeoutError", "Script exceeded its time limit");
        }
        final var opened = simpleJs.openCallable(source, PipelineHostBindings.of(limits(remaining)));
        callables.put(source, opened);
        return opened;
    }

    private ResourceLimits limits(long remainingMillis) {
        final var base = PipelineHostBindings.limitsFromConfiguration();
        return new ResourceLimits(base.instructionBudget(), remainingMillis, base.maxDepth(),
                base.reportUnhandledRejections(), base.fetchEnabled(), base.fetchHostAllowlist(),
                base.maxResponseBytes(), base.fetchTimeoutMillis(), base.strictScriptGoal(), base.textImportEnabled(),
                base.maxModuleDepth(), base.maxLogLines(), base.maxLogLineChars(), base.memoryBudget(),
                base.maxResultBytes(), base.cursorBatchSize(), base.cursorMaxBatchSize());
    }

    @Override
    public void close() {
        for (final var callable : callables.values()) {
            callable.close();
        }
        callables.clear();
    }
}
