package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.Tx2pcCoordinator;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ex.LoggedWriteNotAppliedException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.admin.TransactionRecords;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class Tx2pcCoordinatorIndeterminateTest {
    private static final String REMOTE = "127.0.0.1:59998";
    private final Tx2pcCoordinator coordinator = IocContainer.get(Tx2pcCoordinator.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private PeerConnectionPool origPool;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        origPool = TestUtils.getPrivateField(coordinator, "pool", PeerConnectionPool.class);
        cluster.enableAlone();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenAnswer(invocation -> {
            final ClusterMessage message = invocation.getArgument(1);
            final var reply = new ClusterMessage();
            reply.setType(switch (message.getType()) {
                case PREPARE_TX -> ClusterMessageType.PREPARE_TX_ACK;
                case COMMIT_TX -> ClusterMessageType.COMMIT_TX_ACK;
                default -> ClusterMessageType.ABORT_TX_ACK;
            });
            return reply;
        });
        TestUtils.setPrivateField(coordinator, "pool", pool);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", cache);
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", fs);
        TestUtils.setPrivateField(coordinator, "pool", origPool);
        cluster.disable();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID clientWithLocalAndRemoteSlice() {
        final var id = "tpc-logged";
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        request.setObject(object);
        request.set_id(id);
        processor.processMessage(request, clientId);
        clientTracker.markLocalSlice(clientId);
        clientTracker.addTransactionParticipant(clientId, REMOTE);
        return clientId;
    }

    @Test
    public void aDecisionLoggedButNotAppliedIsAnsweredIndeterminate() throws Exception {
        final var clientId = clientWithLocalAndRemoteSlice();
        final var spy = Mockito.spy(cache);
        Mockito.doThrow(new UncheckedIOException(
                new LoggedWriteNotAppliedException("admin|transactions", new IOException("No space left on device"))))
                .when(spy).putPkIndexTransaction(ArgumentMatchers.argThat(
                        row -> row != null && row.getValue().endsWith("|" + AdminTransactionEntry.MARKER_COORDINATOR)));
        TestUtils.setPrivateStaticField(TransactionRecords.class, "cache", spy);

        final var response = coordinator.commit(clientId);

        assertEquals(ErrorCode.TRANSACTION_INDETERMINATE.getCode(), response.getErrorCode(), response.getMessage());
    }

    private void failTheCoordinatorMarkerAfterLoggingIt() throws Exception {
        final var spy = Mockito.spy(fs);
        Mockito.doThrow(
                new LoggedWriteNotAppliedException("admin|transactions", new IOException("No space left on device")))
                .when(spy).insertIntoCollection(
                        ArgumentMatchers.argThat(entry -> entry != null
                                && entry.get_id().endsWith("|" + AdminTransactionEntry.MARKER_COORDINATOR)),
                        ArgumentMatchers.any());
        TestUtils.setPrivateStaticField(AdminOperationHelper.class, "fs", spy);
    }

    @Test
    public void aRemoteOnlyDecisionLoggedButNotAppliedIsAnsweredIndeterminate() throws Exception {
        final var clientId = clientTracker.addClient(TestUtils.clientSocket());
        processor.processMessage(new StartTransactionRequest(), clientId);
        clientTracker.addTransactionParticipant(clientId, REMOTE);
        failTheCoordinatorMarkerAfterLoggingIt();

        final var response = coordinator.commit(clientId);

        assertEquals(ErrorCode.TRANSACTION_INDETERMINATE.getCode(), response.getErrorCode(), response.getMessage());
    }

    @Test
    public void aForcedDecisionLoggedButNotAppliedIsAnsweredIndeterminate() throws Exception {
        failTheCoordinatorMarkerAfterLoggingIt();

        final var response = coordinator.forceResolve(UUID.randomUUID().toString(), true);

        assertEquals(ErrorCode.TRANSACTION_INDETERMINATE.getCode(), response.getErrorCode(), response.getMessage());
    }
}
