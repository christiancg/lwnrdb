package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.DeleteOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class PkIndexTierRefusedWriteTest {
    private static final String DB = TestGlobals.DB;
    private static final String RESIDENT = TestGlobals.COLL;
    private static final String REFUSED = "refusedWrites";
    private static final int SEEDED = 40;
    private static final long REFUSE_EVERYTHING = 1L;

    private final Configuration configuration = Configuration.getInstance();
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final EJson eJson = new EJson();
    private final BackgroundTaskManager idleTaskManager = new BackgroundTaskManager();
    private BackgroundTaskManager saveTaskManager;
    private BackgroundTaskManager deleteTaskManager;
    private Long savedMaxMemory;
    private WholeLoads realWholeLoads;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(DB, REFUSED));
        fs.createCollectionFile(DB, REFUSED);
        savedMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realWholeLoads = userCache.wholeLoads();
        saveTaskManager = TestUtils.getPrivateStaticField(SaveOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        deleteTaskManager = TestUtils.getPrivateStaticField(DeleteOperationHelper.class, "taskManager",
                BackgroundTaskManager.class);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", idleTaskManager);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", idleTaskManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(userCache, "wholeLoads", realWholeLoads);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", savedMaxMemory);
        TestUtils.setPrivateStaticField(SaveOperationHelper.class, "taskManager", saveTaskManager);
        TestUtils.setPrivateStaticField(DeleteOperationHelper.class, "taskManager", deleteTaskManager);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String id(int i) {
        return "d-" + String.format("%04d", i);
    }

    private static JsonObject document(String id, String payload) {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty("payload", payload);
        return object;
    }

    private void seed(String collName) throws Exception {
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < SEEDED; i++) {
            entries.add(DbEntry.fromJsonObject(DB, collName, document(id(i), "seed-" + i)));
        }
        fs.bulkInsertIntoCollection(DB, collName, entries);
    }

    private List<String> runScript(String collName) throws Exception {
        final var answers = new ArrayList<String>();
        locks.lock(DB, collName);
        try {
            answers.add(eJson.toJson(save(collName, document("new-1", "inserted"))));
            answers.add(eJson.toJson(save(collName, document(id(3), "updated"))));
            answers.add(eJson.toJson(save(collName, document(id(3), "updated again"))));
            answers.add(eJson.toJson(delete(collName, id(5))));
            answers.add(eJson.toJson(delete(collName, "missing")));
            answers.add(eJson.toJson(delete(collName, id(5))));
            final var bulk = new BulkSaveRequest(DB, collName);
            bulk.setObjects(new ArrayList<>(List.of(document(id(6), "bulk-updated"), document("new-2", "bulk-inserted"),
                    document(id(7), "bulk-updated"), document(id(5), "bulk-recreated"))));
            answers.add(eJson.toJson(SaveOperationHelper.executeBulkSave(bulk)));
            answers.add(String.join(",", DeleteOperationHelper.executeBulkDelete(DB, collName,
                    List.of(id(8), "missing", id(9), id(8), "new-2"), (_, _) -> {
                    }, true)));
            answers.add(eJson.toJson(save(collName, document("new-2", "after bulk delete"))));
        } finally {
            locks.release(DB, collName);
        }
        return answers;
    }

    private Object save(String collName, JsonObject object) throws Exception {
        final var request = new SaveRequest(DB, collName);
        request.setObject(object);
        return SaveOperationHelper.executeSave(request);
    }

    private Object delete(String collName, String id) throws Exception {
        final var request = new DeleteRequest(DB, collName);
        request.set_id(id);
        return DeleteOperationHelper.executeDelete(request);
    }

    private Map<String, String> storedDocuments(String collName) throws Exception {
        final var stored = new TreeMap<String, String>();
        for (final var row : fs.readWholePkIndexFile(DB, collName)) {
            final var document = fs.getByIndexEntries(List.of(row)).getFirst();
            stored.put(row.getValue(), eJson.toJson(document.getData()));
        }
        return stored;
    }

    private void refuseThePk() throws Exception {
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", REFUSE_EVERYTHING);
        locks.lock(DB, REFUSED);
        try {
            assertNotNull(userCache.getPkIndexAndLoadIfNecessary(DB, REFUSED));
        } finally {
            locks.release(DB, REFUSED);
        }
        assertNotNull(userCache.sizes().refusedPk(Cache.getCollectionIdentifier(DB, REFUSED)));
    }

    @Test
    public void writersOnARefusedPkPointScanAndAnswerAsWithAResidentOne() throws Exception {
        seed(RESIDENT);
        seed(REFUSED);
        final var residentAnswers = runScript(RESIDENT);
        assertTrue(userCache.pkIndexes().containsKey(Cache.getCollectionIdentifier(DB, RESIDENT)));

        refuseThePk();
        final var spied = spy(realWholeLoads);
        TestUtils.setPrivateField(userCache, "wholeLoads", spied);
        final var refusedAnswers = runScript(REFUSED);

        verify(spied, never()).pkIndex(anyString(), anyString(), anyBoolean(), any());
        verify(spied, never()).transientPkIndex(anyString(), anyString());
        assertEquals(residentAnswers, refusedAnswers);
        assertEquals(storedDocuments(RESIDENT), storedDocuments(REFUSED));
        assertFalse(userCache.pkIndexes().containsKey(Cache.getCollectionIdentifier(DB, REFUSED)));
    }

    @Test
    public void aRefusedPkIsNeverPartlyAdmittedByAWrite() throws Exception {
        seed(REFUSED);
        refuseThePk();

        runScript(REFUSED);

        assertNull(userCache.pkIndexes().get(Cache.getCollectionIdentifier(DB, REFUSED)));
        final var ids = fs.readWholePkIndexFile(DB, REFUSED).stream().map(PkIndexEntry::getValue).toList();
        assertEquals(SEEDED, ids.size());
        assertTrue(ids.containsAll(List.of("new-1", "new-2", id(5))));
        assertFalse(ids.contains(id(8)) || ids.contains(id(9)));
    }

    @Test
    public void indexMaintenanceOnARefusedPkReadsItsBatchByPointScan() throws Exception {
        seed(REFUSED);
        IndexHelper.createIndex(DB, REFUSED, "payload");
        IocContainer.get(Cache.class).getAdminCollectionEntry(DB, REFUSED).setIndexes(Set.of("payload"));
        refuseThePk();
        final var spied = spy(realWholeLoads);
        TestUtils.setPrivateField(userCache, "wholeLoads", spied);

        IndexHelper.bulkUpdateIndexes(DB, REFUSED, List.of(id(1), id(2), "missing"));

        verify(spied, never()).pkIndex(anyString(), anyString(), anyBoolean(), any());
        verify(spied, never()).transientPkIndex(anyString(), anyString());
    }

    @Test
    public void aWriterOnAResidentPkStillEditsTheResidentList() throws Exception {
        seed(RESIDENT);
        runScript(RESIDENT);

        final var resident = userCache.pkIndexes().get(Cache.getCollectionIdentifier(DB, RESIDENT));
        assertNotNull(resident);
        assertEquals(fs.readWholePkIndexFile(DB, RESIDENT).stream().map(PkIndexEntry::getValue).toList(),
                resident.stream().map(PkIndexEntry::getValue).toList());
    }
}
