package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.DeleteTriggerRequest;
import org.techhouse.ops.req.ListTriggersRequest;
import org.techhouse.ops.req.SaveTriggerRequest;
import org.techhouse.ops.req.TestTriggerRequest;
import org.techhouse.ops.resp.DeleteTriggerResponse;
import org.techhouse.ops.resp.ListTriggersResponse;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.resp.SaveTriggerResponse;
import org.techhouse.ops.resp.TestTriggerResponse;

/**
 * Persists and removes a collection's triggers. All of a collection's triggers live in one file beside its
 * schema, so a save rewrites that list atomically and a DROP_COLLECTION removes them with the data. Callers
 * hold the collection write lock (taken in OperationProcessor, mirroring processSaveSchema).
 */
public final class TriggerOperationHelper {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final EJson eJson = IocContainer.get(EJson.class);

    private TriggerOperationHelper() {
    }

    public static OperationResponse executeSave(SaveTriggerRequest request, String actingUser) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.SAVE_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var events = new LinkedHashSet<EventType>();
        if (request.getEvents().isEmpty()) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": at least one event is required",
                    ErrorCode.INVALID_TRIGGER);
        }
        for (final var event : request.getEvents()) {
            final var parsed = parseEvent(event);
            if (parsed == null) {
                return new OperationResponse(OperationType.SAVE_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                        + ": unknown event '" + event + "' (expected CREATED, UPDATED or DELETED)",
                        ErrorCode.INVALID_TRIGGER);
            }
            events.add(parsed);
        }
        final var mode = request.getMode() == null ? TriggerDefinition.MODE_DOCUMENT : request.getMode();
        if (!TriggerDefinition.MODE_DOCUMENT.equals(mode) && !TriggerDefinition.MODE_BATCH.equals(mode)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": mode must be 'document' or 'batch'",
                    ErrorCode.INVALID_TRIGGER);
        }
        final var timing = request.getTiming() == null ? TriggerDefinition.TIMING_AFTER : request.getTiming();
        final var timingError = validateTiming(timing, mode, request.isAllowCascade());
        if (timingError != null) {
            return timingError;
        }
        // A trigger pointing at nothing is a configuration error worth failing loudly, not a run-time
        // surprise on the first write.
        final var procedure = cache.getProcedure(dbName, request.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    "Procedure '" + request.getProcedureName() + "' not found in database '" + dbName + "'",
                    ErrorCode.PROCEDURE_NOT_FOUND);
        }
        final var existingList = new ArrayList<>(cache.getTriggersFor(dbName, collName));
        final var existing = findByName(existingList, request.getName());
        if (request.getIfVersion() != null
                && request.getIfVersion() != (existing == null ? 0L : existing.getVersion())) {
            return new OperationResponse(OperationType.SAVE_TRIGGER, ErrorCode.PROCEDURE_VERSION_CONFLICT);
        }
        final var definition = stampedDefinition(request, existing, actingUser, mode, timing, events);
        existingList.removeIf(trigger -> trigger.getName().equals(definition.getName()));
        existingList.add(definition);
        persist(dbName, collName, existingList);
        return new SaveTriggerResponse("Trigger saved successfully", definition.getVersion(), definition.getDefiner());
    }

    // Every stamped field is computed once, by the coordinator, onto the request, so a peer re-executing
    // this request under REPLICATE_ADMIN writes a byte-identical file. Re-saving re-stamps the definer to
    // the saving user: keeping the original would leave a trigger running with a previous installer's
    // authority after somebody else edited it.
    // A BEFORE hook runs synchronously on the writer's thread with no db binding, so batch mode (an
    // array-in/array-out contract) and allowCascade (there is nothing to cascade from) are not merely
    // unused but meaningless; refusing them here beats silently ignoring them at dispatch.
    private static OperationResponse validateTiming(String timing, String mode, boolean allowCascade) {
        if (!TriggerDefinition.TIMING_AFTER.equals(timing) && !TriggerDefinition.TIMING_BEFORE.equals(timing)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": timing must be 'before' or 'after'",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (!TriggerDefinition.TIMING_BEFORE.equals(timing)) {
            return null;
        }
        if (TriggerDefinition.MODE_BATCH.equals(mode)) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": a before trigger does not support batch mode",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (allowCascade) {
            return new OperationResponse(OperationType.SAVE_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                            + ": a before trigger cannot allow cascade, because it performs no writes",
                    ErrorCode.INVALID_TRIGGER);
        }
        return null;
    }

    private static TriggerDefinition stampedDefinition(SaveTriggerRequest request, TriggerDefinition existing,
            String actingUser, String mode, String timing, LinkedHashSet<EventType> events) {
        final var alreadyStamped = request.getStampedVersion() > 0;
        final var version = alreadyStamped
                ? request.getStampedVersion()
                : (existing == null ? 1L : existing.getVersion() + 1);
        final var updatedAt = alreadyStamped ? request.getStampedUpdatedAt() : System.currentTimeMillis();
        final var updatedBy = alreadyStamped ? request.getStampedUpdatedBy() : actingUser;
        final var definer = alreadyStamped ? request.getStampedDefiner() : actingUser;
        request.setStampedVersion(version);
        request.setStampedUpdatedAt(updatedAt);
        request.setStampedUpdatedBy(updatedBy);
        request.setStampedDefiner(definer);
        final var createdAt = existing == null ? updatedAt : existing.getCreatedAt();
        return new TriggerDefinition(request.getName(), events, request.getProcedureName(), mode, timing,
                request.isAllowCascade(), request.isEnabled(), definer, version, createdAt, updatedAt, updatedBy);
    }

    // The dry run: the same synchronous callable the write path uses, against a caller-supplied document,
    // taking no locks and writing nothing. It is only possible at all because a before trigger is directly
    // callable - an after trigger's dispatch (cascade depth, definer rights, its pending-run record) still
    // has no dry run.
    public static OperationResponse executeTest(TestTriggerRequest request, String actingUser) {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        if (!Configuration.getInstance().isScriptsEnabled()) {
            return new OperationResponse(OperationType.TEST_TRIGGER, ErrorCode.SCRIPTS_DISABLED);
        }
        final var event = parseEvent(request.getEvent());
        if (event == null) {
            return new OperationResponse(
                    OperationType.TEST_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": unknown event '"
                            + request.getEvent() + "' (expected CREATED, UPDATED or DELETED)",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (request.getDocument() == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage() + ": a document is required",
                    ErrorCode.INVALID_TRIGGER);
        }
        final var trigger = findByName(cache.getTriggersFor(dbName, collName), request.getName());
        if (trigger == null) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    "Trigger '" + request.getName() + "' not found on collection '" + collName + "'",
                    ErrorCode.TRIGGER_NOT_FOUND);
        }
        if (!trigger.isBefore()) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                            + ": only a before trigger can be tested, because only it is directly callable",
                    ErrorCode.INVALID_TRIGGER);
        }
        if (!trigger.getEvents().contains(event)) {
            return new OperationResponse(OperationType.TEST_TRIGGER, ErrorCode.INVALID_TRIGGER.getDefaultMessage()
                    + ": trigger '" + trigger.getName() + "' does not watch " + event, ErrorCode.INVALID_TRIGGER);
        }
        final var procedure = cache.getProcedure(dbName, trigger.getProcedureName());
        if (procedure == null || !procedure.isEnabled()) {
            return new OperationResponse(OperationType.TEST_TRIGGER,
                    "Procedure '" + trigger.getProcedureName() + "' not found in database '" + dbName + "'",
                    ErrorCode.PROCEDURE_NOT_FOUND);
        }
        return runTest(request, trigger, event, actingUser);
    }

    private static OperationResponse runTest(TestTriggerRequest request, TriggerDefinition trigger, EventType event,
            String actingUser) {
        final var document = request.getDocument();
        final var id = document.has(Globals.PK_FIELD) && document.get(Globals.PK_FIELD).isJsonString()
                ? document.get(Globals.PK_FIELD).asJsonString().getValue()
                : null;
        try (var hooks = BeforeHookContext.openForTest(request.getDatabaseName(), request.getCollectionName(), event,
                actingUser, trigger)) {
            final var outcome = hooks.apply(document, id, OperationType.TEST_TRIGGER);
            if (!outcome.isRejected()) {
                final var replaced = outcome.document() != document;
                return new TestTriggerResponse("Trigger tested successfully",
                        replaced ? TestTriggerResponse.DECISION_REPLACE : TestTriggerResponse.DECISION_ACCEPT,
                        outcome.document(), null, hooks.logs(), false, null);
            }
            final var rejection = outcome.rejection();
            // A hook that said no is an answer, not a failure; a hook that could not finish (a timeout, an
            // exhausted budget) never made a decision, so it keeps the error the write itself would get.
            if (ErrorCode.BEFORE_HOOK_REJECTED.getCode().equals(rejection.getErrorCode())) {
                return new TestTriggerResponse("Trigger tested successfully", TestTriggerResponse.DECISION_REJECT, null,
                        rejection.getMessage(), hooks.logs(), false, hooks.lastStack());
            }
            return rejection;
        }
    }

    // Idempotent: succeeds whether or not the trigger existed, so cluster re-execution on a peer that has
    // already removed it does not fail replication.
    public static OperationResponse executeDelete(DeleteTriggerRequest request) throws IOException {
        final var dbName = request.getDatabaseName();
        final var collName = request.getCollectionName();
        if (cache.getAdminCollectionEntry(dbName, collName) == null) {
            return new OperationResponse(OperationType.DELETE_TRIGGER, "Collection '" + collName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var remaining = new ArrayList<>(cache.getTriggersFor(dbName, collName));
        remaining.removeIf(trigger -> trigger.getName().equals(request.getName()));
        persist(dbName, collName, remaining);
        return new DeleteTriggerResponse("Trigger deleted successfully");
    }

    public static OperationResponse executeList(ListTriggersRequest request) {
        final var dbName = request.getDatabaseName();
        if (cache.getAdminDbEntry(dbName) == null) {
            return new OperationResponse(OperationType.LIST_TRIGGERS, "Database '" + dbName + "' not found",
                    ErrorCode.DATABASE_NOT_FOUND);
        }
        final var collNames = request.getCollectionName() == null || request.getCollectionName().isBlank()
                ? cache.getCollectionNamesForDatabase(dbName)
                : List.of(request.getCollectionName());
        final var result = new ArrayList<JsonObject>();
        for (final var collName : collNames) {
            for (final var trigger : cache.getTriggersFor(dbName, collName)) {
                final var json = trigger.toJsonObject();
                json.addProperty("collectionName", collName);
                result.add(json);
            }
        }
        return new ListTriggersResponse("Ok", result);
    }

    // The name of a trigger in the database still pointing at the procedure, or null when none does. Only
    // consulted on a procedure delete, so a scan of the database's collections is cheap enough.
    public static String triggerReferencing(String dbName, String procedureName) {
        for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
            for (final var trigger : cache.getTriggersFor(dbName, collName)) {
                if (procedureName.equals(trigger.getProcedureName())) {
                    return trigger.getName();
                }
            }
        }
        return null;
    }

    private static void persist(String dbName, String collName, List<TriggerDefinition> definitions)
            throws IOException {
        if (definitions.isEmpty()) {
            fs.deleteTriggers(dbName, collName);
            cache.removeTriggers(dbName, collName);
            return;
        }
        fs.writeTriggers(dbName, collName, eJson.toJson(TriggerDefinition.toFileJson(definitions)));
        cache.putTriggers(dbName, collName, definitions);
    }

    private static TriggerDefinition findByName(List<TriggerDefinition> definitions, String name) {
        for (final var definition : definitions) {
            if (definition.getName().equals(name)) {
                return definition;
            }
        }
        return null;
    }

    private static EventType parseEvent(String name) {
        for (final var type : EventType.values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }
}
