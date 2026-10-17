package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

public record PipelineHostBindings(ResourceLimits limits, ZoneId timeZone, Locale locale) implements HostBindings {
    private static final Configuration configuration = Configuration.getInstance();

    public static PipelineHostBindings of(ResourceLimits limits) {
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
        return new ResourceLimits(configuration.getAggregationScriptInstructionBudget(),
                configuration.getAggregationScriptTimeoutMs(), configuration.getScriptMaxDepth(), true, false,
                java.util.List.of(), -1, -1, false, false, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH,
                ResourceLimits.DEFAULT_MAX_LOG_LINES, ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS,
                configuration.getScriptMaxMemoryBytes(), -1, -1, -1, ResourceLimits.DEFAULT_FETCH_MAX_CONCURRENT,
                configuration.intValue(ConfigKey.SCRIPT_MAX_ARRAY_LENGTH));
    }
}
