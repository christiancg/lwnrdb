package org.techhouse.test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;

public final class TriggerTestSupport {
    private TriggerTestSupport() {
    }

    public static void createDefiner(String username) throws Exception {
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(username));
        createUser(username);
    }

    public static void createUser(String username) {
        final var request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    public static void clearTriggersAndProcedures() throws Exception {
        final var cache = IocContainer.get(Cache.class);
        final var fs = IocContainer.get(FileSystem.class);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
    }

    public static void consumeAllPendingRuns() throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    public static AdminTriggerRunEntry pendingRun(String runId) throws Exception {
        for (final var run : TriggerRunLog.pending()) {
            if (runId.equals(run.getRunId())) {
                return run;
            }
        }
        return null;
    }

    public static TriggerRunStatus statusOf(String runId) throws Exception {
        final var run = pendingRun(runId);
        return run == null ? null : run.getStatus();
    }

    public static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        return entryOf(id, data);
    }

    public static DbEntry entry(String id, String value) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        data.add("value", new JsonString(value));
        return entryOf(id, data);
    }

    private static DbEntry entryOf(String id, JsonObject data) {
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }
}
