package org.techhouse.ops;

import java.util.List;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.DatabaseHostBindings;
import org.techhouse.simplejs.host.EnforcingDatabaseAccess;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptResult;

/**
 * Runs one queued trigger. A failure here never reaches the write that fired it - that already committed -
 * so every outcome is reported to the log and the counters instead of to a client.
 */
public final class TriggerDispatcher {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScriptRunRegistry runRegistry = IocContainer.get(ScriptRunRegistry.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(TriggerDispatcher.class);

    private TriggerDispatcher() {
    }

    public static void dispatch(TriggerEvent event) {
        if (event.getDepth() >= configuration.getTriggerMaxDepth()) {
            consumeQuietly(event.getRunId(), event.getTriggerName());
            triggerExecutor.countFailure();
            logger.warning("Trigger '" + event.getTriggerName() + "' not run: cascade depth " + event.getDepth()
                    + " reached triggerMaxDepth");
            return;
        }
        // Both may have been dropped while the event was queued - the staleness EventProcessorHelper
        // already guards against for a dropped collection.
        final var trigger = findTrigger(event);
        if (trigger == null || !trigger.isEnabled()) {
            consumeQuietly(event.getRunId(), event.getTriggerName());
            return;
        }
        final var procedure = cache.getProcedure(event.getDbName(), trigger.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            consumeQuietly(event.getRunId(), event.getTriggerName());
            return;
        }
        final var definer = trigger.getDefiner();
        // A definer who no longer exists disables the trigger. Deliberately no fallback: falling back to
        // the writer would silently reinstate invoker rights, and falling back to an admin would let
        // deleting a user widen a trigger's authority.
        if (definer == null || cache.getAdminUserEntry(definer) == null) {
            consumeQuietly(event.getRunId(), event.getTriggerName());
            triggerExecutor.countFailure();
            logger.warning(
                    "Trigger '" + event.getTriggerName() + "' not run: definer '" + definer + "' no longer exists");
            return;
        }
        run(event, trigger, procedure.getVersion(), procedure.getSource(), definer);
    }

    private static void run(TriggerEvent event, TriggerDefinition trigger, long version, String source,
            String definer) {
        final var compiled = compiledProcedures.get(event.getDbName(), trigger.getProcedureName(), version, source);
        final var database = new EnforcingDatabaseAccess(definer, null, event.getDbName(), event.getDepth() + 1);
        final var scriptRun = runRegistry.register(ScriptRunKind.TRIGGER, event.getDbName(), trigger.getName(), definer,
                null);
        final var start = System.currentTimeMillis();
        final var runId = event.getRunId();
        // A logged run executes inside a transaction that also consumes its record, so the effects and the
        // record that would replay them commit together and a replay can never double-apply. Begin and commit
        // both happen inside the body wrapper because the interpreter runs the module on its own thread and
        // the collection locks a transactional write takes are owned by the thread that acquired them.
        final var committed = new java.util.concurrent.atomic.AtomicBoolean(runId == null);
        final ScriptResult result;
        try {
            // Unlike CALL_PROCEDURE the console sink is non-null: nobody is waiting on a response, so the
            // server log is the only place a trigger's console output can go.
            final var host = DatabaseHostBindings.of(argsFor(event, definer), database, logger::info, limits(),
                    scriptRun::isCancelled);
            result = runId == null
                    ? simpleJs.run(compiled, host)
                    : simpleJs.run(compiled, host,
                            body -> runInTransaction(database, runId, trigger.getName(), body, committed));
        } finally {
            runRegistry.unregister(scriptRun.runId());
        }
        final var line = "TRIGGER name=" + trigger.getName() + " database=" + event.getDbName() + " collection="
                + event.getCollName() + " event=" + event.getType() + " definer=" + definer + " actingUser="
                + event.getActingUser() + " runId=" + scriptRun.runId() + " durationMs="
                + (System.currentTimeMillis() - start);
        if (result.isError()) {
            consumeQuietly(runId, trigger.getName());
            triggerExecutor.countFailure();
            logger.warning(line + " outcome=" + result.getErrorName() + ": " + result.getErrorMessage()
                    + ScriptOperationHelper.renderStack(result.getErrorStack())
                    + (result.getLogs().isEmpty() ? "" : " logs=" + result.getLogs()));
            return;
        }
        if (!committed.get()) {
            consumeQuietly(runId, trigger.getName());
            triggerExecutor.countFailure();
            logger.warning(line + " outcome=commit-failed");
            return;
        }
        logger.info(line + " outcome=ok");
    }

    // Runs the script body between a begin and a commit that also consumes the pending run. A body failure
    // propagates after the rollback, so SimpleJs reports it as the run's error.
    private static void runInTransaction(EnforcingDatabaseAccess database, String runId, String triggerName,
            Runnable body, java.util.concurrent.atomic.AtomicBoolean committed) {
        database.beginTransaction();
        try {
            body.run();
        } catch (RuntimeException e) {
            rollbackQuietly(database, triggerName);
            throw e;
        }
        try {
            database.bufferTriggerRunConsume(runId);
            database.commitTransaction();
            committed.set(true);
        } catch (RuntimeException e) {
            logger.error("Failed to commit the effects of trigger '" + triggerName + "': " + e.getMessage(), e);
            rollbackQuietly(database, triggerName);
        }
    }

    private static void rollbackQuietly(EnforcingDatabaseAccess database, String triggerName) {
        try {
            if (database.hasActiveTransaction()) {
                database.rollbackTransaction();
            }
        } catch (RuntimeException e) {
            logger.warning("Failed to roll back trigger '" + triggerName + "': " + e.getMessage());
        }
    }

    // Consumes a run outside any transaction, for the terminal outcomes that apply no effects at all.
    public static void consumeQuietly(String runId, String triggerName) {
        if (runId == null) {
            return;
        }
        try {
            AdminOperationHelper.deleteTriggerRuns(TriggerRunLog.recordIdsFor(runId));
        } catch (Exception e) {
            logger.warning("Failed to consume the pending run of trigger '" + triggerName + "': " + e.getMessage());
        }
    }

    // maxResultBytes stays -1: a trigger's result is discarded, so capping it would fail a run for a
    // value nobody reads.
    private static ResourceLimits limits() {
        final var base = DatabaseHostBindings.limitsFromConfiguration();
        return new ResourceLimits(base.instructionBudget(), configuration.getTriggerTimeoutMs(), base.maxDepth(),
                base.reportUnhandledRejections(), base.fetchEnabled(), base.fetchHostAllowlist(),
                base.maxResponseBytes(), base.fetchTimeoutMillis(), base.strictScriptGoal(), base.textImportEnabled(),
                base.maxModuleDepth(), base.maxLogLines(), base.maxLogLineChars(), base.memoryBudget(), -1,
                base.cursorBatchSize(), base.cursorMaxBatchSize());
    }

    private static JsonObject argsFor(TriggerEvent event, String definer) {
        final var args = new JsonObject();
        args.add("event", new JsonString(event.getType().name()));
        args.add("database", new JsonString(event.getDbName()));
        args.add("collection", new JsonString(event.getCollName()));
        args.add("trigger", new JsonString(event.getTriggerName()));
        args.add("actingUser", new JsonString(event.getActingUser()));
        args.add("definer", new JsonString(definer));
        args.add("firedAt", new JsonNumber(event.getFiredAt()));
        args.add("depth", new JsonNumber(event.getDepth()));
        if (event.isBatchMode()) {
            final var documents = new JsonArray();
            event.getEntries().forEach(entry -> documents.add(entry.getData()));
            args.add("documents", documents);
        } else if (!event.getEntries().isEmpty()) {
            final var entry = event.getEntries().getFirst();
            args.add("id", new JsonString(entry.get_id()));
            args.add("document", entry.getData());
        }
        return args;
    }

    private static TriggerDefinition findTrigger(TriggerEvent event) {
        final List<TriggerDefinition> triggers = cache.getTriggersFor(event.getDbName(), event.getCollName());
        for (final var trigger : triggers) {
            if (trigger.getName().equals(event.getTriggerName())) {
                return trigger;
            }
        }
        return null;
    }
}
