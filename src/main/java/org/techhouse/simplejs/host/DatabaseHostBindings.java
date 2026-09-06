package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

/**
 * The database's own {@link HostBindings}: the same contract {@code SimpleHostBindings} supplies,
 * with the time zone and locale pinned by configuration ({@code scriptTimeZone}/{@code scriptLocale})
 * so a script answers identically on every cluster node. {@code SimpleHostBindings} deliberately
 * keeps the JVM defaults, which is what leaves the test262 worker's behaviour untouched.
 */
public record DatabaseHostBindings(JsonObject args, DatabaseAccess database, Consumer<String> console,
        ResourceLimits limits, ZoneId timeZone, Locale locale, CancellationToken cancellation,
        NetworkAccess network) implements HostBindings {

    // One client per process rather than one per run: HttpClient owns a connection pool and a selector
    // thread, so a run building its own would pay for both and discard them.
    private static final JdkNetworkAccess SHARED_NETWORK = new JdkNetworkAccess();

    public static DatabaseHostBindings of(JsonObject args, DatabaseAccess database, Consumer<String> console,
            ResourceLimits limits, CancellationToken cancellation) {
        final var configuration = Configuration.getInstance();
        return new DatabaseHostBindings(args, database, console, limits, ZoneId.of(configuration.getScriptTimeZone()),
                Locale.forLanguageTag(configuration.getScriptLocale()), cancellation,
                configuration.isScriptFetchEnabled() ? SHARED_NETWORK : null);
    }

    // A new resolver per call rather than a cached one: a record cannot hold an extra instance field, and
    // the resolver is a one-reference flyweight. Requiring a scope keeps an unscoped embedding on its
    // previous behaviour and means the resolver can never be asked about a database this run is not
    // confined to.
    @Override
    public ModuleResolver moduleResolver() {
        if (!Configuration.getInstance().isScriptProcedureImportEnabled() || database == null
                || database.scopedDatabase() == null) {
            return null;
        }
        return new ProcedureModuleResolver(database.scopedDatabase());
    }

    // Network access is off unless scriptFetchEnabled says otherwise, and even then it reaches only the
    // hosts scriptFetchAllowlist names. The parse goal stays relaxed, since the wire contract allows a
    // top-level `return`.
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
