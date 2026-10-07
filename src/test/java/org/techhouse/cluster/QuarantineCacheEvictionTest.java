package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestUtils;

public class QuarantineCacheEvictionTest {
    private static final String DB = "quarantinedb";
    private static final String COLL = "quarantinecoll";
    private static final String PROCEDURE = "oldproc";
    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"legacy\"]}";
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        final var config = mock(ClusterConfig.class);
        when(config.replicationAckTimeoutMs()).thenReturn(150L);
        TestUtils.setPrivateField(conformer, "clusterConfig", config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private boolean conform(AdminSnapshotPayload snapshot) throws Exception {
        return conformer.conform(snapshot, adminEpoch.current());
    }

    private static AdminSnapshotPayload snapshot(List<JsonObject> dbs, List<JsonObject> colls) {
        return new AdminSnapshotPayload(5L, dbs, colls, List.of(), new JsonObject());
    }

    private static JsonObject dbJson() {
        return new AdminDbEntry(DB, new ArrayList<>(), new ArrayList<>()).getData();
    }

    private static JsonObject collJson(long incarnation) {
        final var entry = new AdminCollEntry(DB, COLL, new HashSet<>());
        entry.setIncarnation(incarnation);
        final var json = entry.getData().deepCopy();
        json.addProperty(Globals.PK_FIELD, Cache.getCollectionIdentifier(DB, COLL));
        return json;
    }

    @Test
    public void test_a_schema_loaded_during_a_collection_quarantine_does_not_outlive_the_move() throws Exception {
        conform(snapshot(List.of(dbJson()), List.of(collJson(100L))));
        fs.writeCollectionSchema(DB, COLL, SCHEMA);
        cache.removeCollectionSchema(DB, COLL);

        try (var helper = Mockito.mockStatic(AdminOperationHelper.class, Mockito.CALLS_REAL_METHODS)) {
            helper.when(() -> AdminOperationHelper.deletePageCollections(anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        cache.getCollectionSchema(DB, COLL);
                        return invocation.callRealMethod();
                    });
            assertTrue(conform(snapshot(List.of(dbJson()), List.of(collJson(200L)))));
        }

        assertNotNull(cache.getAdminCollectionEntry(DB, COLL));
        assertNull(cache.getCollectionSchema(DB, COLL),
                "the re-created incarnation has no schema, so the dropped one's must not keep validating writes");
    }

    @Test
    public void test_a_procedure_loaded_during_a_database_quarantine_does_not_outlive_the_move() throws Exception {
        conform(snapshot(List.of(dbJson()), List.of(collJson(100L))));
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(DB, PROCEDURE, "return 1;"), "admin");
        cache.removeProcedure(DB, PROCEDURE);

        try (var helper = Mockito.mockStatic(AdminOperationHelper.class, Mockito.CALLS_REAL_METHODS)) {
            helper.when(() -> AdminOperationHelper.deleteDatabaseEntry(anyString())).thenAnswer(invocation -> {
                cache.getProcedure(DB, PROCEDURE);
                return invocation.callRealMethod();
            });
            assertTrue(conform(snapshot(List.of(), List.of())));
        }

        assertNull(cache.getProcedure(DB, PROCEDURE),
                "a quarantined database's procedure must not stay callable from the cache");
    }
}
