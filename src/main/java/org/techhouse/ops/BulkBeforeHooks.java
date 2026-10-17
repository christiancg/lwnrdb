package org.techhouse.ops;

import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.validate.SchemaBudget;

public final class BulkBeforeHooks implements AutoCloseable {
    private final BeforeHookContext creates;
    private final BeforeHookContext updates;

    private BulkBeforeHooks(BeforeHookContext creates, BeforeHookContext updates) {
        this.creates = creates;
        this.updates = updates;
    }

    public static BulkBeforeHooks open(String dbName, String collName, String actingUser, SchemaBudget schemaBudget) {
        final var creates = BeforeHookContext.open(dbName, collName, EventType.CREATED, actingUser, schemaBudget);
        try {
            return new BulkBeforeHooks(creates,
                    BeforeHookContext.open(dbName, collName, EventType.UPDATED, actingUser, schemaBudget));
        } catch (RuntimeException failure) {
            creates.close();
            throw failure;
        }
    }

    public BeforeHookOutcome apply(boolean isUpdate, JsonObject document, String id, OperationType type) {
        final var hooks = isUpdate ? updates : creates;
        return hooks.isEmpty() ? BeforeHookOutcome.accepted(document) : hooks.apply(document, id, type);
    }

    @Override
    public void close() {
        try (updates) {
            creates.close();
        }
    }
}
