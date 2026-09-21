package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.CompiledProcedureCache;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerClusterRetryTest {
    private static final String DEFINER = "clusterretryowner";
    private static final Configuration configuration = Configuration.getInstance();

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private OwnershipManager realOwnership;

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(DEFINER));
        final var request = new CreateUserRequest();
        request.setUsername(DEFINER);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 1);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 86_400_000L);
        TestUtils.setPrivateField(configuration, "triggerTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
        TestUtils.setPrivateField(configuration, "triggerMaxDepth", 3);
        cache.removeTriggers(TestGlobals.DB, TestGlobals.COLL);
        for (final var name : fs.listProcedureNames(TestGlobals.DB)) {
            fs.deleteProcedure(TestGlobals.DB, name);
        }
        cache.removeProceduresForDatabase(TestGlobals.DB);
        IocContainer.get(CompiledProcedureCache.class).invalidateDatabase(TestGlobals.DB);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        ProcedureOperationHelper.executeSave(
                new SaveProcedureRequest(TestGlobals.DB, "audit", "import db from 'db'; import args from 'args';"
                        + " db.save(db.name, '" + TestGlobals.COLL + "', { _id: 'written-by-' + args.id });"),
                DEFINER);
        cache.putTriggers(TestGlobals.DB, TestGlobals.COLL,
                List.of(new TriggerDefinition("audit", new LinkedHashSet<>(Set.of(EventType.CREATED)), "audit",
                        TriggerDefinition.MODE_DOCUMENT, false, true, DEFINER, 1L, 1L, 1L, DEFINER)));
        realOwnership = TestUtils.getPrivateField(coordinator, "ownershipManager", OwnershipManager.class);
        final var withoutQuorum = mock(OwnershipManager.class);
        when(withoutQuorum.hasQuorum()).thenReturn(false);
        when(withoutQuorum.isOwner(any(), any())).thenReturn(true);
        TestUtils.setPrivateField(coordinator, "ownershipManager", withoutQuorum);
        TestUtils.setPrivateField(router, "ownershipManager", withoutQuorum);
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
    }

    @AfterEach
    void restore() throws Exception {
        TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        TestUtils.setPrivateField(coordinator, "ownershipManager", realOwnership);
        TestUtils.setPrivateField(router, "ownershipManager", realOwnership);
    }

    private static DbEntry entry(String id) {
        final var data = new JsonObject();
        data.add("_id", new JsonString(id));
        final var dbEntry = new DbEntry();
        dbEntry.setDatabaseName(TestGlobals.DB);
        dbEntry.setCollectionName(TestGlobals.COLL);
        dbEntry.set_id(id);
        dbEntry.setData(data);
        return dbEntry;
    }

    private static String recordedRunFor(String id) {
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", EventType.CREATED, false, DEFINER, 0, System.currentTimeMillis(), List.of(entry(id))));
    }

    private static void dispatchLastAttempt(String id, String runId) {
        TriggerDispatcher.dispatch(new TriggerEvent(EventType.CREATED, TestGlobals.DB, TestGlobals.COLL, "audit",
                "audit", false, List.of(entry(id)), DEFINER, 0, runId, 1));
    }

    private static TriggerRunStatus statusOf(String runId) throws Exception {
        for (final var run : TriggerRunLog.pending()) {
            if (runId.equals(run.getRunId())) {
                return run.getStatus();
            }
        }
        return null;
    }

    @Test
    public void test_a_write_refused_for_lack_of_quorum_does_not_consume_an_attempt() throws Exception {
        final var runId = recordedRunFor("waiting");

        dispatchLastAttempt("waiting", runId);

        assertEquals(TriggerRunStatus.PENDING, statusOf(runId),
                "a node that cannot reach quorum has not failed the trigger, so its last attempt must survive");
    }

    @Test
    public void test_the_cluster_wait_is_bounded_by_the_run_retention() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 0L);
        final var runId = recordedRunFor("stranded");

        dispatchLastAttempt("stranded", runId);

        assertEquals(TriggerRunStatus.DEAD, statusOf(runId),
                "a node that permanently lost ownership must become actionable rather than retry forever");
    }
}
