package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

public record HookHostBindings(ResourceLimits limits, ZoneId timeZone, Locale locale, Consumer<String> console,
        ModuleResolver moduleResolver, CancellationToken cancellation) implements HostBindings {
    public static HookHostBindings live(String scopedDatabase, ResourceLimits limits, CancellationToken cancellation) {
        return of(scopedDatabase, limits, _ -> {
        }, cancellation);
    }

    public static HookHostBindings capturing(String scopedDatabase, ResourceLimits limits, Consumer<String> sink,
            CancellationToken cancellation) {
        return of(scopedDatabase, limits, sink, cancellation);
    }

    private static HookHostBindings of(String scopedDatabase, ResourceLimits limits, Consumer<String> sink,
            CancellationToken cancellation) {
        final var configuration = Configuration.getInstance();
        final var resolver = configuration.isScriptProcedureImportEnabled() && scopedDatabase != null
                ? new ProcedureModuleResolver(scopedDatabase)
                : null;
        return new HookHostBindings(limits, ZoneId.of(configuration.getScriptTimeZone()),
                Locale.forLanguageTag(configuration.getScriptLocale()), sink, resolver, cancellation);
    }

    @Override
    public JsonObject args() {
        return null;
    }

    @Override
    public DatabaseAccess database() {
        return null;
    }

    public static ResourceLimits limitsFromConfiguration() {
        final var configuration = Configuration.getInstance();
        return new ResourceLimits(configuration.getBeforeHookInstructionBudget(),
                configuration.getBeforeHookTimeoutMs(), configuration.getScriptMaxDepth(), true, false, List.of(), -1,
                -1, false, false, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, configuration.getScriptMaxMemoryBytes(), -1, -1, -1);
    }
}
