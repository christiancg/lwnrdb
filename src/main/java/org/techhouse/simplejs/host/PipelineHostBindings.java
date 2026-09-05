package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

/**
 * The bindings a script running inside an aggregation pipeline gets: the language and the document it is
 * handed, and nothing else. Every capability is deliberately absent - {@code database()} is null so
 * {@code import db} raises the existing catchable failure, which is what makes the feature safe (the pipeline
 * already holds collection read locks on this thread, and with no {@code db} a pipeline script cannot issue an
 * {@code AGGREGATE} and so has no recursion to bound), and {@code network()}/{@code moduleResolver()} stay
 * null for the same posture they have everywhere else.
 *
 * <p>
 * Console output is discarded rather than captured: the operator is invoked once per document, so a
 * {@code console.log} over a large collection is a log-flood risk, and the operator's window is
 * {@code analyze}. Time zone and locale come from the same configuration keys {@link DatabaseHostBindings}
 * uses, so a computed date field answers identically on every node.
 */
public record PipelineHostBindings(ResourceLimits limits, ZoneId timeZone, Locale locale) implements HostBindings {

    public static PipelineHostBindings of(ResourceLimits limits) {
        final var configuration = Configuration.getInstance();
        return new PipelineHostBindings(limits, ZoneId.of(configuration.getScriptTimeZone()),
                Locale.forLanguageTag(configuration.getScriptLocale()));
    }

    @Override
    public JsonObject args() {
        return null;
    }

    @Override
    public DatabaseAccess database() {
        return null;
    }

    @Override
    public Consumer<String> console() {
        return _ -> {
        };
    }

    public static ResourceLimits limitsFromConfiguration() {
        final var configuration = Configuration.getInstance();
        return new ResourceLimits(configuration.getAggregationScriptInstructionBudget(),
                configuration.getAggregationScriptTimeoutMs(), configuration.getScriptMaxDepth(), true, false,
                java.util.List.of(), -1, -1, false, false, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH,
                ResourceLimits.DEFAULT_MAX_LOG_LINES, ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS,
                configuration.getScriptMaxMemoryBytes(), -1, -1, -1);
    }
}
