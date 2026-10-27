package org.techhouse.ops.tx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.PkIndexList;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.SaveOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.ops.req.DeleteRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionGroupedCommitTest {
    private static final String COLL_LOG = TestGlobals.COLL + ".wal";
    private static final long SMALL_PAGE = 4096L;
    private static final int PAYLOAD = 280;
    private static final int VACUUM_LOG_FORCES = 4;
    private static final Configuration configuration = Configuration.getInstance();
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        recorder.close();
        TestUtils.setPrivateField(configuration, "maxPageSize", 2_097_152L);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject document(String id, String marker) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.add("marker", new JsonString(marker));
        object.add("payload", new JsonString("x".repeat(PAYLOAD)));
        return object;
    }

    private UUID open() {
        final var clientId = clientTracker.registerForwardedClient("grouped");
        TestUtils.ensureAdminUser(clientTracker.getAuthenticatedUsername(clientId));
        TransactionOperationHelper.start(clientId);
        return clientId;
    }

    private void save(UUID clientId, String id, String marker) {
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document(id, marker));
        request.set_id(id);
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferSave(request, clientTracker.getActiveTransaction(clientId)).getStatus());
    }

    private void deleteWarm0(UUID clientId) {
        final var request = new DeleteRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id("warm-0");
        assertEquals(OperationStatus.OK, TransactionOperationHelper
                .bufferDelete(request, clientTracker.getActiveTransaction(clientId)).getStatus());
    }

    private void commitSaves(String prefix, int count) {
        final var clientId = open();
        for (var i = 0; i < count; i++) {
            save(clientId, prefix + i, "v0");
        }
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
    }

    private void warmUp() {
        commitSaves("warm-", 2);
        recorder.clear();
    }

    private long collectionLogForces() {
        return recorder.forcedNames().stream().filter(COLL_LOG::equals).count();
    }

    private String markerOf(String id) throws Exception {
        final var primaryKeyIndex = cache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        final var position = PkIndexList.positionOf(primaryKeyIndex, id);
        if (position < 0) {
            return null;
        }
        final var entry = cache.getById(TestGlobals.DB, TestGlobals.COLL, primaryKeyIndex.get(position).detachedCopy());
        return entry.getData().get("marker").asJsonString().getValue();
    }

    private static long copiesOnDisk(String id) throws Exception {
        final var folder = new File(
                TestGlobals.PATH + Globals.FILE_SEPARATOR + TestGlobals.DB + Globals.FILE_SEPARATOR + TestGlobals.COLL);
        final var needle = "\"_id\":\"" + id + "\"";
        var copies = 0L;
        for (final var page : Objects
                .requireNonNull(folder.listFiles((_, name) -> name.endsWith(Globals.DB_FILE_EXTENSION)))) {
            copies += Files.readAllLines(page.toPath(), StandardCharsets.UTF_8).stream()
                    .filter(line -> line.contains(needle)).count();
        }
        return copies;
    }

    @Test
    public void test_a_failed_group_force_keeps_the_fence_and_a_resent_commit_finishes() throws Exception {
        warmUp();
        final var clientId = open();
        for (var i = 0; i < 5; i++) {
            save(clientId, "fenced-" + i, "v1");
        }
        final var txId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        recorder.failingFor(COLL_LOG);

        final var refused = TransactionOperationHelper.commit(clientId);

        assertEquals(ErrorCode.TRANSACTION_HALF_APPLIED.getCode(), refused.getErrorCode());
        assertTrue(TxCommitLog.isLocallyCommitted(txId), "a failed group force keeps the commit marker");
        assertNotNull(clientTracker.getActiveTransaction(clientId), "the fenced transaction stays registered");
        for (var i = 0; i < 5; i++) {
            assertNull(markerOf("fenced-" + i), "the failed group was taken back");
        }

        recorder.failingFor(null);
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertFalse(TxCommitLog.isLocallyCommitted(txId), "the re-sent commit finished the slice");
        for (var i = 0; i < 5; i++) {
            assertEquals("v1", markerOf("fenced-" + i));
            assertEquals(1, copiesOnDisk("fenced-" + i));
        }
    }

    @Test
    public void test_the_finish_record_is_forced_after_the_collection_log() {
        warmUp();
        final var clientId = open();
        for (var i = 0; i < 3; i++) {
            save(clientId, "ordered-" + i, "v1");
        }

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        final var names = recorder.forcedNames();
        assertEquals(1, collectionLogForces(), "one force for the whole collection: " + names);
        final var collectionForce = names.indexOf(COLL_LOG);
        assertTrue(collectionForce > 0, "the begin record comes first: " + names);
        assertTrue(collectionForce < names.size() - 1, "the finish record is forced after the data: " + names);
        assertEquals(names.getFirst(), names.getLast(), "begin and finish both go to admin/transactions: " + names);
    }

    @Test
    public void test_the_same_id_saved_twice_in_one_commit_keeps_one_copy() throws Exception {
        commitSaves("dup", 1);
        warmUp();
        final var clientId = open();
        save(clientId, "dup0", "first");
        save(clientId, "dup0", "second");
        save(clientId, "other", "v1");
        deleteWarm0(clientId);

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        assertEquals(1, collectionLogForces(), recorder.forcedNames().toString());
        assertEquals("second", markerOf("dup0"));
        assertEquals(1, copiesOnDisk("dup0"), "both superseded copies are blanked once the group settles");
        assertEquals(0, copiesOnDisk("warm-0"));
        assertNull(markerOf("warm-0"));
    }

    @Test
    public void test_a_vacuum_inside_the_commit_reopens_the_group() throws Exception {
        TestUtils.setPrivateField(configuration, "maxPageSize", SMALL_PAGE);
        commitSaves("d", 6);
        warmUp();
        final var updates = 40;
        final var clientId = open();
        for (var round = 1; round <= updates; round++) {
            save(clientId, "d" + round % 6, "v" + round);
        }

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());

        final var names = recorder.forcedNames();
        final var vacuums = names.stream().filter(name -> name.endsWith(".vacuum")).count();
        assertTrue(vacuums > 0, "the churn vacuumed the page: " + names);
        assertTrue(collectionLogForces() <= VACUUM_LOG_FORCES * vacuums + 1,
                "only the settle, the barrier frames and the update that vacuumed force on their own; the writes after"
                        + " each vacuum are grouped again: " + names);
        for (var i = 0; i < 6; i++) {
            assertEquals("v" + (updates - Math.floorMod(updates - i, 6)), markerOf("d" + i));
            assertEquals(1, copiesOnDisk("d" + i));
        }
    }

    @Test
    public void test_without_sync_writes_no_group_is_opened() throws Exception {
        final var clientId = open();
        save(clientId, "unsynced-0", "v1");
        save(clientId, "unsynced-1", "v1");
        final var ops = clientTracker.getActiveTransaction(clientId).getPendingOps();
        assertNotSame(CommitWriteGroups.NONE, CommitWriteGroups.plan(ops), "two writes on one collection group");
        assertSame(CommitWriteGroups.NONE, CommitWriteGroups.plan(ops.subList(0, 1)),
                "a write group of one is a plain write");
        TestUtils.setPrivateField(configuration, "syncWrites", "false");

        assertSame(CommitWriteGroups.NONE, CommitWriteGroups.plan(ops));
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        assertEquals("v1", markerOf("unsynced-1"));
    }

    @Test
    public void test_an_op_failing_once_is_retried_inside_the_open_group() throws Exception {
        warmUp();
        final var clientId = open();
        for (var i = 0; i < 4; i++) {
            save(clientId, "retried-" + i, "v1");
        }
        final var calls = new int[1];
        try (var mocked = mockStatic(SaveOperationHelper.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> SaveOperationHelper.executeSave(any(), anyLong(), any())).thenAnswer(invocation -> {
                if (calls[0]++ == 2) {
                    throw new IllegalStateException("transient failure");
                }
                return invocation.callRealMethod();
            });

            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        }

        assertEquals(5, calls[0], "the failed op ran again");
        assertEquals(3, recorder.forcedNames().size(), "the retry stayed in the group: " + recorder.forcedNames());
        for (var i = 0; i < 4; i++) {
            assertEquals("v1", markerOf("retried-" + i));
        }
    }
}
