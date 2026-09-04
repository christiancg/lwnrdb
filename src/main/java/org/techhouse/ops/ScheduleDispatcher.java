package org.techhouse.ops;

import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.ResourceLimits;

/**
 * Runs one due schedule. Nobody is waiting on the result, so every outcome goes to the log and the counters.
 *
 * <p>
 * A scheduled run uses <em>definer rights</em>, like a trigger and for the same reason: it has no caller, so
 * running it as its installer makes it behave identically regardless of who happens to be connected. A
 * definer who no longer exists disables the schedule rather than falling back to anybody — falling back to an
 * admin would let deleting a user widen a job's authority.
 *
 * <p>
 * Deliberately <em>not</em> transactional. A trigger runs inside a transaction because its pending-run record
 * must be consumed atomically with its effects; a schedule has no run record, so wrapping it would only hold
 * collection write locks for the whole job. A scheduled procedure that wants atomicity opens its own
 * {@code db.transaction(...)}, which — unlike inside a trigger — is permitted.
 */
public final class ScheduleDispatcher {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(ScheduleDispatcher.class);

    private ScheduleDispatcher() {
    }

    public static void dispatch(ScheduleRegistry.Entry entry) {
        final var definition = entry.getDefinition();
        final var dbName = entry.getDbName();
        // The definition may have been dropped or disabled while the run sat in the queue.
        if (!definition.isEnabled()) {
            scheduleExecutor.countSkip();
            return;
        }
        final var procedure = cache.getProcedure(dbName, definition.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            scheduleExecutor.countSkip();
            registry.warnOnce(entry.key(),
                    "procedure '" + definition.getProcedureName() + "' is missing or disabled; nothing ran");
            return;
        }
        final var definer = definition.getDefiner();
        if (definer == null || cache.getAdminUserEntry(definer) == null) {
            scheduleExecutor.countSkip();
            registry.warnOnce(entry.key(), "definer '" + definer + "' no longer exists; the schedule is disabled");
            return;
        }
        run(entry, definition, procedure.getVersion(), procedure.getSource(), definer);
    }

    private static void run(ScheduleRegistry.Entry entry, ScheduleDefinition definition, long version, String source,
            String definer) {
        final var dbName = entry.getDbName();
        final var compiled = compiledProcedures.get(dbName, definition.getProcedureName(), version, source);
        final var logPrefix = "SCHEDULE name=" + definition.getName() + " database=" + dbName + " procedure="
                + definition.getProcedureName() + " definer=" + definer;
        final var result = ScriptOperationHelper.runCompiled(compiled, definition.getArgs(), dbName, definer, null,
                logPrefix, ScriptRunKind.SCHEDULE, definition.getName(), limits(definition), logger::info).result();
        if (result.isError()) {
            scheduleExecutor.countFailure();
            logger.warning(logPrefix + " outcome=" + result.getErrorName() + ": " + result.getErrorMessage()
                    + (result.getLogs().isEmpty() ? "" : " logs=" + result.getLogs()));
        }
    }

    // The configured sandbox with its wall clock replaced by the schedule's own timeoutMs (falling back to
    // scheduleTimeoutMs), the same override TriggerDispatcher performs. maxResultBytes stays -1 because a
    // scheduled run's result is discarded, so capping it would fail a run for a value nobody reads.
    private static ResourceLimits limits(ScheduleDefinition definition) {
        final var base = DatabaseHostBindings.limitsFromConfiguration();
        final var timeout = definition.getTimeoutMs() > 0
                ? definition.getTimeoutMs()
                : configuration.getScheduleTimeoutMs();
        return new ResourceLimits(base.instructionBudget(), timeout, base.maxDepth(), base.reportUnhandledRejections(),
                base.fetchEnabled(), base.fetchHostAllowlist(), base.maxResponseBytes(), base.fetchTimeoutMillis(),
                base.strictScriptGoal(), base.textImportEnabled(), base.maxModuleDepth(), base.maxLogLines(),
                base.maxLogLineChars(), base.memoryBudget(), -1, base.cursorBatchSize(), base.cursorMaxBatchSize());
    }
}
