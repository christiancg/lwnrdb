package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

public record DatabaseHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits, ZoneId timeZone, Locale locale, CancellationToken cancellation,
        NetworkAccess network) implements HostBindings {
    private static final JdkNetworkAccess SHARED_NETWORK = new JdkNetworkAccess();

    public static DatabaseHostBindings of(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits, CancellationToken cancellation) {
        final var configuration = Configuration.getInstance();
        return new DatabaseHostBindings(args, database, console, limits, ZoneId.of(configuration.getScriptTimeZone()),
                Locale.forLanguageTag(configuration.getScriptLocale()), cancellation,
                configuration.isScriptFetchEnabled() ? SHARED_NETWORK : null);
    }

    @Override
    public ModuleResolver moduleResolver() {
        if (!Configuration.getInstance().isScriptProcedureImportEnabled() || database == null
                || database.scopedDatabase() == null) {
            return null;
        }
        return new ProcedureModuleResolver(database.scopedDatabase());
    }

    public static ResourceLimits limitsFromConfiguration() {
        final var configuration = Configuration.getInstance();
        return new ResourceLimits(configuration.getScriptInstructionBudget(), configuration.getScriptTimeoutMs(),
                configuration.getScriptMaxDepth(), true, configuration.isScriptFetchEnabled(),
                configuration.getScriptFetchAllowlist(), configuration.getScriptFetchMaxResponseBytes(),
                configuration.getScriptFetchTimeoutMs(), false, configuration.isScriptTextImportEnabled(),
                ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, configuration.getScriptMaxLogLines(),
                configuration.getScriptMaxLogLineChars(), configuration.getScriptMaxMemoryBytes(),
                configuration.getScriptMaxResultBytes(), configuration.getScriptCursorBatchSize(),
                configuration.getScriptCursorMaxBatchSize());
    }
}
