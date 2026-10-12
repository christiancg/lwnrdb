package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.Transaction;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.ops.tx.CommitAuthorization;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionLimitsTest {
    private static final String WRITER = "tx_limited_writer";
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private UUID clientId;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        grantCollection(PermissionLevel.READ_WRITE);
        clientId = clientTracker.registerForwardedClient(WRITER);
        TransactionOperationHelper.start(clientId);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientTracker.getActiveTransaction(clientId) != null) {
            TransactionOperationHelper.abort(clientId);
        }
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "transactionMaxOps", ConfigKey.TRANSACTION_MAX_OPS.defaultValue());
        TestUtils.setPrivateField(configuration, "transactionMaxBytes", ConfigKey.TRANSACTION_MAX_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void grantCollection(PermissionLevel level) throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry(WRITER, "h", false, Set.of(), new HashMap<>(),
                new HashMap<>(Map.of(TestGlobals.DB + "|" + TestGlobals.COLL, level))));
    }

    private OperationResponse send(String json) {
        return processor.processMessage(RequestParser.parseRequest(json), clientId);
    }

    private static String save(String id, String payload) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"p\":\"" + payload + "\"}}";
    }

    private static final String BULK_SAVE_TWO = "{\"type\":\"BULK_SAVE\",\"databaseName\":\"" + TestGlobals.DB
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"objects\":[{\"_id\":\"b1\"},{\"_id\":\"b2\"}]}";
    private static final String DELETE_OK1 = "{\"type\":\"DELETE\",\"databaseName\":\"" + TestGlobals.DB
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"ok1\"}";

    private static boolean collectionIsWritable() throws InterruptedException {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = new java.util.concurrent.atomic.AtomicBoolean();
        final var probe = Thread.ofPlatform().start(() -> {
            free.set(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
            if (free.get()) {
                locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        probe.join(5000);
        return free.get();
    }

    private OperationResponse findById(String id) {
        return processor.processMessage(RequestParser.parseRequest("{\"type\":\"FIND_BY_ID\",\"databaseName\":\""
                + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}"));
    }

    @Test
    public void test_a_write_past_the_operation_limit_is_refused_and_the_transaction_still_commits() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionMaxOps", 2);
        assertEquals(OperationStatus.OK, send(save("ok1", "x")).getStatus());
        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), send(BULK_SAVE_TWO).getErrorCode());
        assertEquals(OperationStatus.OK, send(save("ok2", "x")).getStatus());
        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), send(save("over", "x")).getErrorCode());
        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), send(DELETE_OK1).getErrorCode());

        assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
        assertEquals(OperationStatus.OK, findById("ok1").getStatus());
        assertEquals(OperationStatus.OK, findById("ok2").getStatus());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), findById("over").getErrorCode());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), findById("b1").getErrorCode());
    }

    @Test
    public void test_a_write_refused_for_its_operation_count_never_takes_the_collection_lock() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionMaxOps", 1);
        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), send(BULK_SAVE_TWO).getErrorCode());
        assertTrue(collectionIsWritable());
        assertTrue(clientTracker.getActiveTransaction(clientId).getHeldLocks().isEmpty());
    }

    @Test
    public void test_a_commit_with_no_user_is_an_internal_caller_and_is_not_rechecked() {
        assertEquals(OperationStatus.OK, send(save("held", "x")).getStatus());
        assertTrue(CommitAuthorization.allows(clientTracker.getActiveTransaction(clientId), null));
        assertFalse(CommitAuthorization.allows(clientTracker.getActiveTransaction(clientId), "no_such_user"));
    }

    @Test
    public void test_a_write_past_the_byte_limit_is_refused_and_rollback_still_works() throws Exception {
        TestUtils.setPrivateField(configuration, "transactionMaxBytes", "1Kb");
        assertEquals(OperationStatus.OK, send(save("small", "x")).getStatus());
        assertEquals(ErrorCode.TRANSACTION_TOO_LARGE.getCode(), send(save("big", "y".repeat(2000))).getErrorCode());
        assertEquals(OperationStatus.OK, TransactionOperationHelper.abort(clientId).getStatus());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), findById("small").getErrorCode());
    }

    @Test
    public void test_a_commit_after_the_write_grant_was_revoked_is_refused_and_applies_nothing() throws Exception {
        assertEquals(OperationStatus.OK, send(save("revoked", "x")).getStatus());
        grantCollection(PermissionLevel.READ);

        final var response = TransactionOperationHelper.commit(clientId);

        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), response.getErrorCode());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), findById("revoked").getErrorCode());
        assertTrue(collectionIsWritable());
    }

    @Test
    public void test_a_commit_after_the_user_was_deleted_is_refused() throws Exception {
        assertEquals(OperationStatus.OK, send(save("orphan", "x")).getStatus());
        AdminOperationHelper.deleteUserEntry(WRITER);

        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), TransactionOperationHelper.commit(clientId).getErrorCode());
        assertEquals(ErrorCode.ENTRY_NOT_FOUND.getCode(), findById("orphan").getErrorCode());
    }

    @Test
    public void test_the_transaction_counts_what_it_buffers() {
        final var transaction = new Transaction(UUID.randomUUID(), UUID.randomUUID());
        assertFalse(transaction.wouldExceed(2, 10, 2, 100));
        transaction.accountBuffered(2, 10);
        assertTrue(transaction.wouldExceed(1, 0, 2, 100));
        assertTrue(transaction.wouldExceed(0, 91, 0, 100));
        assertFalse(transaction.wouldExceed(1_000, 0, 0, 100));
        assertTrue(transaction.ageMillis() >= 0);
    }

    @Test
    public void test_a_database_owner_without_grants_still_commits() throws Exception {
        assertEquals(OperationStatus.OK, send(save("owned", "x")).getStatus());
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry(WRITER, "h", false, Set.of(), new HashMap<>(), new HashMap<>()));
        AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of(WRITER));
        try {
            assertEquals(OperationStatus.OK, TransactionOperationHelper.commit(clientId).getStatus());
            assertEquals(OperationStatus.OK, findById("owned").getStatus());
        } finally {
            AdminOperationHelper.updateDatabaseOwners(TestGlobals.DB, List.of());
        }
    }

    @Test
    public void test_a_two_phase_participant_votes_no_once_the_grant_was_revoked() throws Exception {
        assertEquals(OperationStatus.OK, send(save("prepared", "x")).getStatus());
        assertTrue(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:1", List.of()));
        grantCollection(PermissionLevel.READ);
        assertFalse(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:1", List.of()));
    }
}
