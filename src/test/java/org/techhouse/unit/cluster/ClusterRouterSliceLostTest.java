package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.techhouse.test.ClusterTestHarness.node;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.OperationRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterSliceLostTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Configuration config = Configuration.getInstance();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private String coll;
    private UUID clientId;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        cluster.start(false, 10_000L);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        cluster.configureMembership(2, node("self", 19990), node("other", cluster.serverPort()));
        coll = collectionOwnedByOther();
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
        clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var sessionId : clientTracker.txSessionsSnapshot().keySet()) {
            clientTracker.removeTxSession(sessionId);
        }
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var candidate = "slice-lost-" + i;
            if (!ownership.isOwner(TestGlobals.DB, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private UUID newClient() {
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private OperationResponse route(OperationRequest request) {
        return eJson.fromJson(router.forward(request, eJson.toJson(request), true, "admin", clientId),
                OperationResponse.class);
    }

    private OperationResponse saveRemotely(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        final var save = new SaveRequest(TestGlobals.DB, coll);
        save.setObject(object);
        save.set_id(id);
        return route(save);
    }

    private OperationResponse findLostRemotely() {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id("lost");
        return route(find);
    }

    private OperationStatus committedStatus(String id) {
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id(id);
        return processor.processMessage(find).getStatus();
    }

    private void participantLosesTheSlice() {
        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));
    }

    @Test
    public void test_the_first_write_to_an_owner_starts_its_slice() throws Exception {
        assertNull(saveRemotely("first").getErrorCode());
        assertNull(saveRemotely("second").getErrorCode(), "a continuation of a live slice is served");
        cluster.configureMembership(1, node("other", cluster.serverPort()));

        assertNull(route(new CommitTransactionRequest()).getErrorCode());
        assertEquals(OperationStatus.OK, committedStatus("first"));
        assertEquals(OperationStatus.OK, committedStatus("second"));
    }

    @Test
    public void test_a_write_after_the_owner_lost_the_slice_is_refused() {
        saveRemotely("lost");
        participantLosesTheSlice();

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), saveRemotely("after").getErrorCode());
        assertNotEquals(OperationStatus.OK, route(new CommitTransactionRequest()).getStatus(),
                "a commit must never report the half of the transaction that survived as the whole");
        assertEquals(OperationStatus.NOT_FOUND, committedStatus("lost"));
        assertEquals(OperationStatus.NOT_FOUND, committedStatus("after"));
    }

    @Test
    public void test_a_read_after_the_owner_lost_the_slice_is_refused() {
        saveRemotely("lost");
        participantLosesTheSlice();

        assertEquals(ErrorCode.TRANSACTION_SLICE_LOST.getCode(), findLostRemotely().getErrorCode(),
                "a read would otherwise start a fresh slice that no longer sees the transaction's own write");
    }
}
