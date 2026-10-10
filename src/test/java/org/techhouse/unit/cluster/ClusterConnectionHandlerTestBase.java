package org.techhouse.unit.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public abstract class ClusterConnectionHandlerTestBase {
    protected static final long ACK_TIMEOUT_MS = 30000L;
    protected static final long SHORT_ACK_TIMEOUT_MS = 3000L;
    protected final ClusterTestHarness cluster = new ClusterTestHarness();
    protected final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    protected final PeerConnectionPool pool = new PeerConnectionPool();
    protected final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    protected final EJson eJson = IocContainer.get(EJson.class);
    protected final Configuration config = Configuration.getInstance();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start(true, ACK_TIMEOUT_MS);
        cluster.configureMembership(1,
                new NodeInfo("self", "127.0.0.1", cluster.serverPort(), NodeState.ALIVE, 1L, 1L));
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected ClusterMessage envelope(ClusterMessageType type) {
        final var message = new ClusterMessage();
        message.setType(type);
        message.setSecret(ClusterTestHarness.SECRET);
        return message;
    }

    protected OperationResponse findById(String id) {
        final var json = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}";
        return processor.processMessage(RequestParser.parseRequest(json));
    }
}
