package org.techhouse.ops;

import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.TriggerRunStatus;
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
    private static final String CANCELLED_ERROR = "ScriptCancelledError";

    private TriggerDispatcher() {
    }

    public static void dispatch(TriggerEvent event) {
        if (event.getDepth() >= configuration.getTriggerMaxDepth()) {
            consumeQuietly(event.getRunId(), event.getTriggerName());
            triggerExecutor.countFailure();
            final var reason = "cascade depth " + event.getDepth() + " reached triggerMaxDepth";
            logger.warning("Trigger '" + event.getTriggerName() + "' not run: " + reason);
            recordSkip(event, reason);
            return;
        }
        // Both may have been dropped while the event was queued - the staleness EventProcessorHelper
        // already guards against for a dropped collection.
        final var trigger = findTrigger(event);
        if (trigger == null || !trigger.isEnabled() || trigger.isBefore()) {
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
            final var reason = "definer '" + definer + "' no longer exists";
            logger.warning("Trigger '" + event.getTriggerName() + "' not run: " + reason);
            recordSkip(event, reason);
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
            triggerExecutor.countFailure();
            logger.warning(line + " outcome=" + result.getErrorName() + ": " + result.getErrorMessage()
                    + ScriptOperationHelper.renderStack(result.getErrorStack())
                    + (result.getLogs().isEmpty() ? "" : " logs=" + result.getLogs()));
            // A cancellation is the operator saying stop, so it is terminal however many attempts remain -
            // the one place the exactly-once guarantee is deliberately waived.
            final var retryable = !CANCELLED_ERROR.equals(result.getErrorName());
            handleFailure(event, trigger, definer, scriptRun.runId(), start, result.getErrorName(),
                    result.getErrorMessage(), result.getErrorStack(), result, retryable);
            return;
        }
        if (!committed.get()) {
            triggerExecutor.countFailure();
            logger.warning(line + " outcome=commit-failed");
            handleFailure(event, trigger, definer, scriptRun.runId(), start, "CommitFailed",
                    "the trigger's effects could not be committed", null, result, true);
            return;
        }
        logger.info(line + " outcome=ok");
        recordRun(event, trigger, definer, scriptRun.runId(), start, ScriptRunRecord.OUTCOME_OK, null, null, null,
                result);
    }

    /**
     * Decides what a failed run becomes: another attempt after a backoff, or a dead letter.
     *
     * <p>
     * The pending record is the state machine - a retry leaves it PENDING with a raised attempt count, and a
     * dead letter marks it DEAD with its last error while keeping the payload, which is what lets an operator
     * replay it later. Only a run that is out of attempts (or was never retryable) consumes its record, so
     * the exactly-once property is unchanged: a record still present is still un-applied.
     */
    private static void handleFailure(TriggerEvent event, TriggerDefinition trigger, String definer, String runId,
            long start, String errorName, String errorMessage, List<String> stack, ScriptResult result,
            boolean retryable) {
        final var attempt = event.getAttempt();
        final var maxAttempts = Math.max(1, configuration.getTriggerMaxAttempts());
        final var error = errorName + ": " + errorMessage;
        if (retryable && event.getRunId() != null && attempt < maxAttempts) {
            final var delay = backoffFor(attempt);
            TriggerRunLog.markAttempt(event.getRunId(), TriggerRunStatus.PENDING, attempt, error,
                    System.currentTimeMillis() + delay);
            triggerExecutor.submitAfter(retryOf(event), delay);
            logger.info("Trigger '" + trigger.getName() + "' attempt " + attempt + " of " + maxAttempts
                    + " failed; retrying in " + delay + "ms");
            recordRun(event, trigger, definer, runId, start, ScriptRunRecord.OUTCOME_ERROR, errorName, errorMessage,
                    stack, result);
            return;
        }
        if (retryable && event.getRunId() != null) {
            TriggerRunLog.markAttempt(event.getRunId(), TriggerRunStatus.DEAD, attempt, error, 0L);
            triggerExecutor.countDeadLetter();
            logger.warning("Trigger '" + trigger.getName() + "' failed " + attempt
                    + " attempt(s) and was dead-lettered; runId=" + event.getRunId()
                    + " - resolve it with RESOLVE_TRIGGER_RUN");
            recordRun(event, trigger, definer, runId, start, ScriptRunRecord.OUTCOME_DEAD_LETTER, errorName,
                    errorMessage, stack, result);
            return;
        }
        consumeQuietly(event.getRunId(), trigger.getName());
        recordRun(event, trigger, definer, runId, start, ScriptRunRecord.OUTCOME_ERROR, errorName, errorMessage, stack,
                result);
    }

    // Doubling from the configured base, clamped: the point is to outlast a transient lock or an unreachable
    // peer, not to schedule a run beyond the operator's patience.
    public static long backoffFor(int attempt) {
        final var base = Math.max(0L, configuration.getTriggerRetryBackoffMs());
        final var ceiling = Math.max(0L, configuration.getTriggerRetryMaxBackoffMs());
        if (base == 0L) {
            return 0L;
        }
        final var exponent = Math.min(attempt - 1, 32);
        final var delay = base << exponent;
        return delay < 0 || delay > ceiling ? ceiling : delay;
    }

    private static TriggerEvent retryOf(TriggerEvent event) {
        return new TriggerEvent(event.getType(), event.getDbName(), event.getCollName(), event.getTriggerName(),
                event.getProcedureName(), event.isBatchMode(), event.getEntries(), event.getActingUser(),
                event.getDepth(), event.getRunId(), event.getAttempt() + 1);
    }

    private static void recordRun(TriggerEvent event, TriggerDefinition trigger, String definer, String runId,
            long start, String outcome, String errorName, String errorMessage, List<String> stack,
            ScriptResult result) {
        ScriptRunHistory.record(new ScriptRunRecord(runId, ScriptRunKind.TRIGGER, event.getDbName(), trigger.getName(),
                trigger.getProcedureName(), event.getCollName(), event.getType().name(), definer, event.getActingUser(),
                start, System.currentTimeMillis() - start, event.getAttempt(), outcome, errorName, errorMessage, stack,
                result.getMetrics(), result.getLogs(), result.isLogsTruncated()));
    }

    // A trigger that never ran leaves no other trace than a log line, which is exactly the outcome an
    // operator is most likely to be hunting for.
    private static void recordSkip(TriggerEvent event, String reason) {
        ScriptRunHistory.record(new ScriptRunRecord(UUID.randomUUID().toString(), ScriptRunKind.TRIGGER,
                event.getDbName(), event.getTriggerName(), event.getProcedureName(), event.getCollName(),
                event.getType().name(), null, event.getActingUser(), System.currentTimeMillis(), 0L, event.getAttempt(),
                ScriptRunRecord.OUTCOME_SKIPPED, null, reason, null, null, null, false));
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
