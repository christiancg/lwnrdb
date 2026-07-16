package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnection;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ejson.EJson;

public class PeerConnectionTest {
    private final EJson eJson = new EJson();

    private ClusterMessage gossip() {
        return new ClusterMessage(null, ClusterMessageType.GOSSIP, "s",
                new NodeInfo("x", "127.0.0.1", 1, NodeState.ALIVE, 1L, 1L), null);
    }

    @Test
    public void test_send_request_completes_on_response() throws Exception {
        try (var listener = new ServerSocket(0)) {
            final var responder = Thread.ofVirtual().start(() -> respondOnce(listener));
            try (var socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                final var connection = new PeerConnection(socket);
                final var response = connection.sendRequest(gossip(), 3000);
                assertEquals(ClusterMessageType.GOSSIP_ACK, response.getType());
                connection.close();
            }
            responder.join();
        }
    }

    @Test
    public void test_send_request_times_out_when_no_response() throws Exception {
        try (var listener = new ServerSocket(0)) {
            final var holder = Thread.ofVirtual().start(() -> acceptAndHold(listener));
            try (var socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                final var connection = new PeerConnection(socket);
                assertThrows(TimeoutException.class, () -> connection.sendRequest(gossip(), 200));
                connection.close();
                connection.close();
                assertTrue(connection.isClosed());
            }
            holder.interrupt();
        }
    }

    @Test
    public void test_reader_closes_connection_on_eof() throws Exception {
        final PeerConnection connection;
        try (var listener = new ServerSocket(0)) {
            final var closer = Thread.ofVirtual().start(() -> acceptAndClose(listener));
            final var socket = new Socket("127.0.0.1", listener.getLocalPort());
            connection = new PeerConnection(socket);
            closer.join();
        }
        for (var i = 0; i < 100 && !connection.isClosed(); i++) {
            Thread.sleep(10);
        }
        assertTrue(connection.isClosed());
    }

    private void respondOnce(ServerSocket listener) {
        try (var socket = listener.accept()) {
            final var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            final var writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            final var request = eJson.fromJson(reader.readLine(), ClusterMessage.class);
            final var response = new ClusterMessage(request.getCorrelationId(), ClusterMessageType.GOSSIP_ACK, "s",
                    null, null);
            writer.write(eJson.toJson(response));
            writer.newLine();
            writer.flush();
        } catch (Exception ignored) {
            // test helper, nothing to do
        }
    }

    private void acceptAndHold(ServerSocket listener) {
        try (var socket = listener.accept()) {
            // Keep the connection open (no response) so the client's request times out.
            socket.setKeepAlive(true);
            Thread.sleep(1500);
        } catch (Exception ignored) {
            // test helper, nothing to do
        }
    }

    private void acceptAndClose(ServerSocket listener) {
        try {
            listener.accept().close();
        } catch (Exception ignored) {
            // test helper, nothing to do
        }
    }
}
