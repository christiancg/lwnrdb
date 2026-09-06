package org.techhouse.simplejs.host;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonObject;

/**
 * The bindings a before-write hook gets. It runs synchronously on the writing thread, inside the collection
 * write lock, so {@code database()} is null for the reason {@link PipelineHostBindings} gives: a re-entrant
 * {@code db} call would take a lock this thread already holds (or, under clustering, a network round trip
 * while a writer's lock is held), and with no {@code db} a hook performs no writes and so has no cascade to
 * bound. {@code network()} stays null for the posture it has everywhere else.
 *
 * <p>
 * The one capability kept, and the one divergence from the pipeline bindings, is {@code moduleResolver()}: a
 * hook must be able to {@code import} a shared procedure, which resolves against already-cached metadata and
 * takes no locks.
 *
 * <p>
 * Console output is discarded on the live path - a hook runs once per document, so a {@code console.log}
 * over a bulk save is a log-flood risk - and captured on the {@code TEST_TRIGGER} path, which is the window
 * that replaces it.
 */
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

    // maxResultBytes stays -1: what a hook returns is the document itself, bounded downstream by
    // maxEntrySize, so the script result cap would fail a write for a value that is about to be
    // size-checked anyway.
    public static ResourceLimits limitsFromConfiguration() {
        final var configuration = Configuration.getInstance();
        return new ResourceLimits(configuration.getBeforeHookInstructionBudget(),
                configuration.getBeforeHookTimeoutMs(), configuration.getScriptMaxDepth(), true, false, List.of(), -1,
                -1, false, false, ResourceLimits.DEFAULT_MAX_MODULE_DEPTH, ResourceLimits.DEFAULT_MAX_LOG_LINES,
                ResourceLimits.DEFAULT_MAX_LOG_LINE_CHARS, configuration.getScriptMaxMemoryBytes(), -1, -1, -1);
    }
}
