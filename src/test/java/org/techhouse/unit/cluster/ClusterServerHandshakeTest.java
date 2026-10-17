package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestUtils;

public class ClusterServerHandshakeTest {
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final long SHORT_HANDSHAKE_MS = 300L;
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Configuration config = Configuration.getInstance();
    private final EJson eJson = IocContainer.get(EJson.class);
    private final List<Socket> sockets = new ArrayList<>();
    private long origHandshake;
    private long origFrame;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        origHandshake = config.longValue(ConfigKey.CLUSTER_HANDSHAKE_TIMEOUT_MS);
        origFrame = config.sizeValue(ConfigKey.CLUSTER_MAX_FRAME_SIZE);
        cluster.start();
        cluster.configureMembership(1, ClusterTestHarness.node("self", cluster.serverPort()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (final var socket : sockets) {
            socket.close();
        }
        cluster.stop();
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", origHandshake);
        TestUtils.setPrivateField(config, "clusterMaxFrameSize", origFrame);
        TestUtils.standardTearDown();
    }

    private Socket connect() throws IOException {
        final var socket = new Socket("127.0.0.1", cluster.serverPort());
        socket.setSoTimeout(READ_TIMEOUT_MS);
        sockets.add(socket);
        return socket;
    }

    private ClusterMessage message(ClusterMessageType type, String secret) {
        final var message = new ClusterMessage(null, type, secret, ClusterTestHarness.node("peer", 1), null);
        message.setCorrelationId("c-" + type);
        return message;
    }

    private void send(Socket socket, ClusterMessage message) throws IOException {
        sendRaw(socket, eJson.toJson(message) + "\n");
    }

    private static void sendRaw(Socket socket, String text) throws IOException {
        final OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private ClusterMessage reply(Socket socket) throws IOException {
        final var line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                .readLine();
        assertNotNull(line, "the connection closed instead of answering");
        return eJson.fromJson(line, ClusterMessage.class);
    }

    private static boolean closedWithoutReply(Socket socket) throws IOException {
        try {
            return socket.getInputStream().read() < 0;
        } catch (SocketException reset) {
            return true;
        }
    }

    private Socket authenticated() throws IOException {
        final var socket = connect();
        send(socket, message(ClusterMessageType.HELLO, ClusterTestHarness.SECRET));
        return socket;
    }

    @Test
    public void aConnectionThatSendsNothingIsClosedAfterTheHandshakeTimeout() throws Exception {
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", SHORT_HANDSHAKE_MS);
        final var socket = connect();
        final var start = System.currentTimeMillis();
        assertTrue(closedWithoutReply(socket));
        assertTrue(System.currentTimeMillis() - start < READ_TIMEOUT_MS);
    }

    @Test
    public void blankLinesDoNotExtendTheHandshakeDeadline() throws Exception {
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", SHORT_HANDSHAKE_MS);
        final var socket = connect();
        sendRaw(socket, "\n\n");
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void aFirstFrameThatIsNotHelloIsClosedWithoutAReply() throws Exception {
        final var socket = connect();
        send(socket, message(ClusterMessageType.GOSSIP, ClusterTestHarness.SECRET));
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void aHelloWithTheWrongSecretIsClosedWithoutAReply() throws Exception {
        final var socket = connect();
        send(socket, message(ClusterMessageType.HELLO, "wrong"));
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void anUnparseableFirstLineIsClosedWithoutAReply() throws Exception {
        final var socket = connect();
        sendRaw(socket, "{not json\n");
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void aFirstLineOverFourKilobytesIsClosedWithoutBeingParsed() throws Exception {
        final var socket = connect();
        sendRaw(socket, "a".repeat(Globals.CLUSTER_HANDSHAKE_MAX_LINE_BYTES + 100));
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void aValidHelloThenFramesAreServedAndTheTimeoutIsCleared() throws Exception {
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", SHORT_HANDSHAKE_MS);
        final var socket = authenticated();
        Thread.sleep(SHORT_HANDSHAKE_MS * 3);
        send(socket, message(ClusterMessageType.GOSSIP, ClusterTestHarness.SECRET));
        assertEquals(ClusterMessageType.GOSSIP_ACK, reply(socket).getType());
    }

    @Test
    public void afterHelloAFrameWithAWrongSecretStillAnswersAnError() throws Exception {
        final var socket = authenticated();
        send(socket, message(ClusterMessageType.GOSSIP, "wrong"));
        final var answer = reply(socket);
        assertEquals(ClusterMessageType.ERROR, answer.getType());
        assertEquals("Invalid cluster secret", answer.getErrorMessage());
    }

    @Test
    public void aSecondHelloIsAnsweredAsUnsupportedAndTheConnectionStaysUsable() throws Exception {
        final var socket = authenticated();
        final var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        send(socket, message(ClusterMessageType.HELLO, ClusterTestHarness.SECRET));
        final var unsupported = eJson.fromJson(Objects.requireNonNull(reader.readLine()), ClusterMessage.class);
        assertEquals("Unsupported cluster message type: HELLO", unsupported.getErrorMessage());
        send(socket, message(ClusterMessageType.GOSSIP, ClusterTestHarness.SECRET));
        assertEquals(ClusterMessageType.GOSSIP_ACK,
                eJson.fromJson(Objects.requireNonNull(reader.readLine()), ClusterMessage.class).getType());
    }

    @Test
    public void aFrameOverClusterMaxFrameSizeClosesTheConnection() throws Exception {
        TestUtils.setPrivateField(config, "clusterMaxFrameSize", "8kb");
        final var socket = authenticated();
        sendRaw(socket, "x".repeat(16 * 1024) + "\n");
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    public void moreThanTheUnauthenticatedCapIsRefusedAndTheCounterRecovers() throws Exception {
        TestUtils.setPrivateField(config, "clusterHandshakeTimeoutMs", 60_000L);
        final var silent = new ArrayList<Socket>();
        for (var i = 0; i < Globals.CLUSTER_MAX_UNAUTHENTICATED_CONNECTIONS; i++) {
            silent.add(connect());
        }
        assertTrue(closedWithoutReply(connect()), "the connection over the cap must be closed at once");
        silent.getFirst().close();
        assertTrue(eventuallyServed(), "closing a silent connection must free its handshake slot");
    }

    private boolean eventuallyServed() throws Exception {
        final var deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                final var socket = authenticated();
                send(socket, message(ClusterMessageType.GOSSIP, ClusterTestHarness.SECRET));
                final var line = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (line != null) {
                    return true;
                }
            } catch (SocketException reset) {
                Thread.onSpinWait();
            }
        }
        return false;
    }

    @Test
    public void aDeeplyNestedFirstLineIsClosedWithoutAReply() throws Exception {
        final var socket = connect();
        sendRaw(socket, "[".repeat(2000) + "]".repeat(2000) + "\n");
        assertTrue(closedWithoutReply(socket));
    }

    @Test
    @SuppressWarnings("resource")
    public void oneAddressCannotHoldMoreThanItsShareOfPreAuthenticationSlots() throws Exception {
        for (var i = 0; i < Globals.CLUSTER_MAX_UNAUTHENTICATED_PER_ADDRESS; i++) {
            connect();
        }
        Thread.sleep(200);
        final var refused = connect();
        final var start = System.currentTimeMillis();
        assertTrue(closedWithoutReply(refused));
        assertTrue(System.currentTimeMillis() - start < 2_000, "the extra connection was held, not refused");
    }
}
