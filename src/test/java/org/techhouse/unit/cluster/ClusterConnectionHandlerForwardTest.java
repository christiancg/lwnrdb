package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AntiEntropyPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.conn.ClientTracker;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;

public class ClusterConnectionHandlerForwardTest extends ClusterConnectionHandlerTestBase {
    private static final String READER = "fwdreader";
    private static final String INVALID_PAYLOAD = "Rejected replicated payload: invalid collection or document id";
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);

    @BeforeEach
    public void createReader() {
        final var request = new CreateUserRequest();
        request.setUsername(READER);
        request.setPassword("password123");
        request.setAdmin(false);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>(Map.of(TestGlobals.DB, PermissionLevel.READ)));
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
    }

    private static String save(String db, String coll, String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + db + "\",\"collectionName\":\"" + coll
                + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
    }

    private OperationResponse forward(String body, String actingUser) throws Exception {
        final var message = envelope(ClusterMessageType.FORWARD_REQUEST);
        message.setActingUser(actingUser);
        message.setForwardBody(ForwardBody.encode(body));
        final var reply = pool.request(cluster.serverAddress(), message, SHORT_ACK_TIMEOUT_MS);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, reply.getType(), reply.getErrorMessage());
        return eJson.fromJson(ForwardBody.decode(reply.getForwardBody()), OperationResponse.class);
    }

    private ClusterMessage send(ClusterMessage message) throws Exception {
        return pool.request(cluster.serverAddress(), message, SHORT_ACK_TIMEOUT_MS);
    }

    @Test
    public void aForwardedSaveWithoutPermissionAnswers403_1AndWritesNothing() throws Exception {
        final var response = forward(save(TestGlobals.DB, TestGlobals.COLL, "denied"), READER);
        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findById("denied").getStatus());
    }

    @Test
    public void aForwardedRequestWithATraversalNameAnswers400_1() throws Exception {
        final var response = forward(save("../x", TestGlobals.COLL, "t1"), ClusterTestHarness.ACTING_ADMIN);
        assertEquals(ErrorCode.VALIDATION_ERROR.getCode(), response.getErrorCode());
    }

    @Test
    public void aForwardedRequestWithoutAnActingUserAnswers401_1() throws Exception {
        final var response = forward(save(TestGlobals.DB, TestGlobals.COLL, "anon"), null);
        assertEquals(ErrorCode.MUST_AUTHENTICATE_FIRST.getCode(), response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findById("anon").getStatus());
    }

    @Test
    public void aForwardedScriptRunsHistoryWriteWithoutAUserIsApplied() throws Exception {
        final var create = forward("{\"type\":\"CREATE_COLLECTION\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"script_runs\"}", null);
        assertEquals(OperationStatus.OK, create.getStatus(), create.getMessage());
        final var saved = forward(save(TestGlobals.DB, "script_runs", "run-1"), null);
        assertEquals(OperationStatus.OK, saved.getStatus(), saved.getMessage());
    }

    @Test
    public void aReindexBroadcastWithABadNameIsRefused() throws Exception {
        final var message = envelope(ClusterMessageType.REINDEX_BROADCAST);
        message.setForwardBody(ForwardBody.encode(
                "{\"type\":\"REINDEX\",\"databaseName\":\"../x\",\"collectionName\":\"" + TestGlobals.COLL + "\"}"));
        final var reply = send(message);
        assertEquals(ClusterMessageType.ERROR, reply.getType());
        assertTrue(reply.getErrorMessage().startsWith("Refused the index rebuild"), reply.getErrorMessage());
    }

    @Test
    public void aForwardedTxRefusalCreatesNoSession() throws Exception {
        final var sessionId = UUID.randomUUID().toString();
        final var message = envelope(ClusterMessageType.FORWARD_TX_REQUEST);
        message.setActingUser(READER);
        message.setTxSessionId(sessionId);
        message.setTxId(UUID.randomUUID().toString());
        message.setForwardBody(ForwardBody.encode(save(TestGlobals.DB, TestGlobals.COLL, "txdenied")));
        final var reply = send(message);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, reply.getType());
        final var response = eJson.fromJson(ForwardBody.decode(reply.getForwardBody()), OperationResponse.class);
        assertEquals(ErrorCode.NO_PERMISSIONS.getCode(), response.getErrorCode());
        assertNull(clientTracker.txSession(sessionId));
    }

    @Test
    public void aReplicateIntoATraversalCollectionIsRefused() throws Exception {
        final var document = new JsonObject();
        document.addProperty("_id", "r1");
        final var message = envelope(ClusterMessageType.REPLICATE);
        message.setReplication(
                new ReplicationPayload("../x", TestGlobals.COLL, ReplicationOp.UPSERT, List.of(document), null));
        final var reply = send(message);
        assertEquals(ClusterMessageType.ERROR, reply.getType());
        assertEquals(INVALID_PAYLOAD, reply.getErrorMessage());
    }

    @Test
    public void aReplicatedTransactionWithOneBadEntryIsRefused() throws Exception {
        final var message = envelope(ClusterMessageType.REPLICATE_TX);
        message.setTxReplication(new TxReplicationPayload(List.of(
                new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.DELETE, null, List.of("a/b")))));
        final var reply = send(message);
        assertEquals(ClusterMessageType.ERROR, reply.getType());
        assertEquals(INVALID_PAYLOAD, reply.getErrorMessage());
    }

    @Test
    public void digestAndPullForAReservedDatabaseAreRefused() throws Exception {
        for (final var type : List.of(ClusterMessageType.DIGEST, ClusterMessageType.PULL)) {
            final var message = envelope(type);
            message.setAntiEntropy(new AntiEntropyPayload("admin", "users"));
            final var reply = send(message);
            assertEquals(ClusterMessageType.ERROR, reply.getType());
            assertEquals(INVALID_PAYLOAD, reply.getErrorMessage());
        }
    }
}
