package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRegistry;
import org.techhouse.test.TestUtils;

/**
 * The two new cluster messages over a real {@code ClusterServer}: what an operator's LIST_SCRIPTS and
 * CANCEL_SCRIPT actually put on the wire when the run they are after is executing on another node.
 */
public class ScriptRunClusterIntegrationTest {
    private static final String SECRET = "s";
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final ScriptRunRegistry registry = IocContainer.get(ScriptRunRegistry.class);
    private ClusterServer server;
    private int serverPort;
    private String origSecret;
    private boolean origTls;

    @BeforeEach
    public void setUp() throws Exception {
        origSecret = config.getClusterSecret();
        origTls = config.isClusterTlsEnabled();
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        server = new ClusterServer(0, "127.0.0.1", null);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    public void tearDown() throws Exception {
        registry.list().forEach(run -> registry.unregister(run.runId()));
        pool.closeAll();
        server.stop();
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", origTls);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
    }

    private ClusterMessage send(ClusterMessage message) throws Exception {
        return pool.request(new NodeAddress("127.0.0.1", serverPort), message, 3000);
    }

    private static ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, SECRET, null, null);
    }

    @Test
    public void test_list_scripts_over_the_wire_reports_the_running_runs() throws Exception {
        final var run = registry.register(ScriptRunKind.CALL_PROCEDURE, "shop", "reprice", "alice", null);
        final var ack = send(message(ClusterMessageType.LIST_SCRIPTS));
        assertEquals(ClusterMessageType.LIST_SCRIPTS_ACK, ack.getType());
        assertNotNull(ack.getRunningScripts());
        final var row = ack.getRunningScripts().stream().filter(r -> run.runId().equals(r.getRunId())).findFirst();
        assertTrue(row.isPresent());
        assertEquals("CALL_PROCEDURE", row.get().getKind());
        assertEquals("shop", row.get().getDatabase());
        assertEquals("reprice", row.get().getName());
        assertEquals("alice", row.get().getUsername());
        assertEquals(run.startedAt(), row.get().getStartedAt());
    }

    @Test
    public void test_list_scripts_over_the_wire_is_empty_when_nothing_runs() throws Exception {
        final var ack = send(message(ClusterMessageType.LIST_SCRIPTS));
        assertEquals(ClusterMessageType.LIST_SCRIPTS_ACK, ack.getType());
        assertTrue(ack.getRunningScripts().isEmpty());
    }

    @Test
    public void test_cancel_script_over_the_wire_cancels_the_run() throws Exception {
        final var run = registry.register(ScriptRunKind.RUN_SCRIPT, "shop", null, "alice", null);
        final var message = message(ClusterMessageType.CANCEL_SCRIPT);
        message.setCancelRunId(run.runId());
        final var ack = send(message);
        assertEquals(ClusterMessageType.CANCEL_SCRIPT_ACK, ack.getType());
        assertTrue(ack.isCancelledRun());
        assertTrue(run.isCancelled());
    }

    // A node that is not running the named run answers false rather than failing: only the node that has
    // it answers true, which is what makes the operator's fan-out safe.
    @Test
    public void test_cancel_script_over_the_wire_answers_false_for_an_unknown_run() throws Exception {
        final var message = message(ClusterMessageType.CANCEL_SCRIPT);
        message.setCancelRunId(UUID.randomUUID().toString());
        final var ack = send(message);
        assertEquals(ClusterMessageType.CANCEL_SCRIPT_ACK, ack.getType());
        assertFalse(ack.isCancelledRun());
    }

    @Test
    public void test_the_wrong_secret_is_rejected_for_both_messages() throws Exception {
        final var list = new ClusterMessage(null, ClusterMessageType.LIST_SCRIPTS, "wrong", null, null);
        assertEquals(ClusterMessageType.ERROR, send(list).getType());
        final var cancel = new ClusterMessage(null, ClusterMessageType.CANCEL_SCRIPT, "wrong", null, null);
        cancel.setCancelRunId(UUID.randomUUID().toString());
        assertEquals(ClusterMessageType.ERROR, send(cancel).getType());
    }
}
