package org.techhouse.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.simplejs.ScriptCallable;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ScriptCallableException;
import org.techhouse.simplejs.host.HookHostBindings;
import org.techhouse.simplejs.host.ResourceLimits;

/**
 * The before-write hooks of one request. Every matching trigger runs synchronously on the calling thread,
 * inside the collection write lock, before the document reaches the write helpers - so a hook can veto a
 * write or replace the document, which an after trigger cannot do because its write has already committed.
 *
 * <p>
 * One callable per procedure per <em>request</em>, not per document, mirroring {@code PipelineScriptContext}:
 * a BULK_SAVE of N documents evaluates each module body once and shares one instruction budget, one deadline
 * and one memory budget across all N invocations, so a runaway hook aborts the request instead of getting a
 * fresh budget on every row.
 *
 * <p>
 * Every failure rejects the write. A hook that could not run must never let a write through, or the
 * guarantee this exists to provide would be best-effort.
 */
public final class BeforeHookContext implements AutoCloseable {
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final SimpleJs simpleJs = IocContainer.get(SimpleJs.class);
    private static final CompiledProcedureCache compiledProcedures = IocContainer.get(CompiledProcedureCache.class);
    private static final ScriptRunRegistry runRegistry = IocContainer.get(ScriptRunRegistry.class);
    private static final Configuration configuration = Configuration.getInstance();

    private static final AtomicLong applied = new AtomicLong();
    private static final AtomicLong replaced = new AtomicLong();
    private static final AtomicLong rejected = new AtomicLong();
    private static final AtomicLong failed = new AtomicLong();

    private final String dbName;
    private final String collName;
    private final EventType event;
    private final String actingUser;
    private final List<TriggerDefinition> hooks;
    private final Map<String, ScriptCallable> callables = new HashMap<>();
    private final long deadline;
    private final List<String> logs;
    // One run per request, not per document: the budgets are per request, and the module body is evaluated
    // inside openCallable, so a per-invocation registration would leave that window uncancellable.
    private final ScriptRun scriptRun;
    private List<String> lastStack;

    private BeforeHookContext(String dbName, String collName, EventType event, String actingUser,
            List<TriggerDefinition> hooks, boolean capturing) {
        this.dbName = dbName;
        this.collName = collName;
        this.event = event;
        this.actingUser = actingUser;
        this.hooks = hooks;
        this.logs = capturing ? new ArrayList<>() : null;
        this.scriptRun = hooks.isEmpty()
                ? null
                : runRegistry.register(ScriptRunKind.BEFORE_HOOK, dbName, collName, actingUser, null);
        this.deadline = System.currentTimeMillis() + configuration.getBeforeHookTimeoutMs();
    }

    public static boolean hasHooksFor(String dbName, String collName, EventType event) {
        return !hooksFor(dbName, collName, event).isEmpty();
    }

    public static BeforeHookContext open(String dbName, String collName, EventType event, String actingUser) {
        return new BeforeHookContext(dbName, collName, event, actingUser, hooksFor(dbName, collName, event), false);
    }

    // The TEST_TRIGGER path: one named hook, and console output kept rather than discarded. The live path
    // discards it because a hook runs once per document, which makes this the window that replaces it.
    public static BeforeHookContext openForTest(String dbName, String collName, EventType event, String actingUser,
            TriggerDefinition hook) {
        return new BeforeHookContext(dbName, collName, event, actingUser, List.of(hook), true);
    }

    public List<String> logs() {
        return logs == null ? List.of() : List.copyOf(logs);
    }

    public List<String> lastStack() {
        return lastStack;
    }

    // Ascending name order rather than the file's insertion order, so a chain of hooks is stable and
    // predictable from what LIST_TRIGGERS shows regardless of the order the rows were installed in.
    private static List<TriggerDefinition> hooksFor(String dbName, String collName, EventType event) {
        // The reserved history collection is written by the server, not by a client, so no hook may veto
        // or rewrite a row - the same exclusion TriggerHelper applies on the after side.
        if (!configuration.isTriggersEnabled() || Globals.SCRIPT_RUNS_COLLECTION_NAME.equals(collName)) {
            return List.of();
        }
        final var matching = new ArrayList<TriggerDefinition>();
        for (final var trigger : cache.getTriggersFor(dbName, collName)) {
            if (trigger.isBefore() && trigger.isEnabled() && trigger.getEvents().contains(event)) {
                matching.add(trigger);
            }
        }
        matching.sort(Comparator.comparing(TriggerDefinition::getName));
        return matching;
    }

    public boolean isEmpty() {
        return hooks.isEmpty();
    }

    public BeforeHookOutcome apply(JsonObject document, String id, OperationType type) {
        var current = document;
        for (final var hook : hooks) {
            final var outcome = runOne(hook, current, id, type);
            if (outcome.isRejected()) {
                return outcome;
            }
            current = outcome.document();
        }
        applied.incrementAndGet();
        return BeforeHookOutcome.accepted(current);
    }

    // The callable is deliberately not closed here: its lifetime is the context's, not this call's. It is
    // cached in `callables` and closed by close(), which is what lets one module evaluation and one budget
    // serve every document in the request - closing it per document would defeat the whole design.
    @SuppressWarnings("resource")
    private BeforeHookOutcome runOne(TriggerDefinition hook, JsonObject document, String id, OperationType type) {
        final var procedure = cache.getProcedure(dbName, hook.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            failed.incrementAndGet();
            return reject(type, hook, "its procedure '" + hook.getProcedureName() + "' no longer exists");
        }
        final ScriptCallable callable;
        try {
            callable = callableFor(hook, procedure.getVersion(), procedure.getSource());
        } catch (ScriptCallableException e) {
            lastStack = e.getErrorStack();
            return failure(type, hook, e);
        }
        final JsonBaseElement returned;
        try {
            returned = callable.applyWithContext(document, contextFor(hook, id));
        } catch (ScriptCallableException e) {
            lastStack = e.getErrorStack();
            return failure(type, hook, e);
        }
        return interpret(hook, document, returned, type);
    }

    private BeforeHookOutcome interpret(TriggerDefinition hook, JsonObject document, JsonBaseElement returned,
            OperationType type) {
        if (returned == null || returned.isJsonNull() || (returned instanceof JsonBoolean bool && bool.getValue())) {
            return BeforeHookOutcome.accepted(document);
        }
        if (!returned.isJsonObject()) {
            rejected.incrementAndGet();
            return reject(type, hook, "it returned " + describe(returned) + " rather than a document or nothing");
        }
        if (event == EventType.DELETED) {
            rejected.incrementAndGet();
            return reject(type, hook, "it returned a document on a DELETED event, which replaces nothing");
        }
        final var replacement = returned.asJsonObject();
        final var idError = checkId(document, replacement);
        if (idError != null) {
            rejected.incrementAndGet();
            return reject(type, hook, idError);
        }
        final var schemaErrors = SchemaValidationHelper.schemaErrors(dbName, collName, replacement);
        if (schemaErrors != null) {
            rejected.incrementAndGet();
            return reject(type, hook, "the document it returned does not match the collection schema: " + schemaErrors);
        }
        replaced.incrementAndGet();
        return BeforeHookOutcome.accepted(replacement);
    }

    // The _id is the primary key the write is addressed by; letting a hook change it would relocate the
    // document rather than modify it, silently turning an update into an insert somewhere else.
    private static String checkId(JsonObject document, JsonObject replacement) {
        final var original = document.has(Globals.PK_FIELD) ? document.get(Globals.PK_FIELD).toString() : null;
        final var returned = replacement.has(Globals.PK_FIELD) ? replacement.get(Globals.PK_FIELD).toString() : null;
        if (original == null && returned == null) {
            return null;
        }
        if (original == null || !original.equals(returned)) {
            return "the document it returned changed _id, which a before trigger may not do";
        }
        return null;
    }

    private ScriptCallable callableFor(TriggerDefinition hook, long version, String source) {
        final var key = hook.getProcedureName() + Globals.COLL_IDENTIFIER_SEPARATOR + version;
        final var existing = callables.get(key);
        if (existing != null) {
            return existing;
        }
        final var remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new ScriptCallableException("ScriptTimeoutError", "Script exceeded its time limit");
        }
        final var compiled = compiledProcedures.get(dbName, hook.getProcedureName(), version, source);
        final var bindings = logs == null
                ? HookHostBindings.live(dbName, limits(remaining), this::isCancelled)
                : HookHostBindings.capturing(dbName, limits(remaining), logs::add, this::isCancelled);
        final var opened = simpleJs.openCallable(compiled, bindings);
        callables.put(key, opened);
        return opened;
    }

    private static ResourceLimits limits(long remainingMillis) {
        final var base = HookHostBindings.limitsFromConfiguration();
        return new ResourceLimits(base.instructionBudget(), remainingMillis, base.maxDepth(),
                base.reportUnhandledRejections(), base.fetchEnabled(), base.fetchHostAllowlist(),
                base.maxResponseBytes(), base.fetchTimeoutMillis(), base.strictScriptGoal(), base.textImportEnabled(),
                base.maxModuleDepth(), base.maxLogLines(), base.maxLogLineChars(), base.memoryBudget(),
                base.maxResultBytes(), base.cursorBatchSize(), base.cursorMaxBatchSize());
    }

    private JsonObject contextFor(TriggerDefinition hook, String id) {
        final var context = new JsonObject();
        context.add("event", new JsonString(event.name()));
        context.add("database", new JsonString(dbName));
        context.add("collection", new JsonString(collName));
        context.add("trigger", new JsonString(hook.getName()));
        context.add("actingUser", new JsonString(actingUser == null ? "" : actingUser));
        context.add("firedAt", new JsonNumber(System.currentTimeMillis()));
        if (id != null) {
            context.add("id", new JsonString(id));
        }
        return context;
    }

    private static String describe(JsonBaseElement value) {
        if (value.isJsonArray()) {
            return "an array";
        }
        if (value instanceof JsonBoolean) {
            return "false";
        }
        return "the value " + value;
    }

    private BeforeHookOutcome reject(OperationType type, TriggerDefinition hook, String reason) {
        recordRefusal(hook, "reject", null, reason);
        return BeforeHookOutcome.rejected(
                new OperationResponse(type, "Before trigger '" + hook.getName() + "' rejected this write: " + reason,
                        ErrorCode.BEFORE_HOOK_REJECTED));
    }

    // Only a refused write is recorded: a hook runs once per document on the write path, so recording every
    // acceptance would multiply the write amplification of the collection it guards.
    private void recordRefusal(TriggerDefinition hook, String outcome, String errorName, String reason) {
        ScriptRunHistory.record(
                new ScriptRunRecord(scriptRun == null ? null : scriptRun.runId(), ScriptRunKind.BEFORE_HOOK, dbName,
                        hook.getName(), hook.getProcedureName(), collName, event.name(), hook.getDefiner(), actingUser,
                        System.currentTimeMillis(), 0L, 1, outcome, errorName, reason, lastStack, null, null, false));
    }

    // A hook that threw decided no, and reads as a rejection (400-21). A sandbox abort keeps its own code
    // (408-1, 400-11, 400-12) so an operator can tell a hook that said no from one that never finished.
    // Both stop the write: a hook that could not run must never let a write through.
    private BeforeHookOutcome failure(OperationType type, TriggerDefinition hook, ScriptCallableException e) {
        final var code = ScriptOperationHelper.errorCodeFor(e.getErrorName());
        if (code == ErrorCode.SCRIPT_FAILED) {
            rejected.incrementAndGet();
            return reject(type, hook, e.getErrorName() + ": " + e.getMessage());
        }
        failed.incrementAndGet();
        recordRefusal(hook, ScriptRunRecord.OUTCOME_ERROR, e.getErrorName(), e.getMessage());
        return BeforeHookOutcome.rejected(new OperationResponse(type,
                "Before trigger '" + hook.getName() + "' failed: " + e.getErrorName() + ": " + e.getMessage(), code));
    }

    public static long getApplied() {
        return applied.get();
    }

    public static long getReplaced() {
        return replaced.get();
    }

    public static long getRejected() {
        return rejected.get();
    }

    public static long getFailed() {
        return failed.get();
    }

    private boolean isCancelled() {
        return scriptRun != null && scriptRun.isCancelled();
    }

    @Override
    public void close() {
        for (final var callable : callables.values()) {
            callable.close();
        }
        callables.clear();
        if (scriptRun != null) {
            runRegistry.unregister(scriptRun.runId());
        }
    }
}
