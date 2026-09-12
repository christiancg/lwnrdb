package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;

// Lives in org.techhouse.cluster to reach the package's internals.
public class PeerConnectionPoolTest {
    private static final long GIVE_UP_MS = 250L;
    private static final long REPLY_DELAY_MS = GIVE_UP_MS * 3;
    private static final long PATIENT_MS = GIVE_UP_MS * 40;
    private final EJson eJson = IocContainer.get(EJson.class);
    private final PeerConnectionPool pool = new PeerConnectionPool();
    private ServerSocket server;

    @BeforeEach
    public void setUp() throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        server.close();
    }

    private NodeAddress address() {
        return new NodeAddress(server.getInetAddress().getHostAddress(), server.getLocalPort());
    }

    // Replies after REPLY_DELAY_MS off the read loop, so a patient and an impatient caller on the same
    // socket stay independent of which request was written first.
    private void serveEveryRequestSlowly(CountDownLatch bothArrived) {
        Thread.ofVirtual().start(() -> {
            try (var socket = server.accept();
                    var reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(
                            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                final var writerLock = new ReentrantLock();
                String line;
                while ((line = reader.readLine()) != null) {
                    final var request = eJson.fromJson(line, ClusterMessage.class);
                    bothArrived.countDown();
                    Thread.ofVirtual().start(() -> replyAfterDelay(writer, writerLock, request));
                }
            } catch (IOException ignored) {
            }
        });
    }

    private void replyAfterDelay(BufferedWriter writer, ReentrantLock writerLock, ClusterMessage request) {
        try {
            Thread.sleep(REPLY_DELAY_MS);
            final var reply = new ClusterMessage();
            reply.setType(ClusterMessageType.GOSSIP_ACK);
            reply.setCorrelationId(request.getCorrelationId());
            writerLock.lock();
            try {
                writer.write(eJson.toJson(reply));
                writer.newLine();
                writer.flush();
            } finally {
                writerLock.unlock();
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // The regression: a timeout used to drop the shared connection, failing every other request riding
    // on it - which cost the cluster its write quorum.
    @Test
    public void test_one_request_timing_out_leaves_a_concurrent_request_on_the_same_peer_alive() throws Exception {
        final var bothArrived = new CountDownLatch(2);
        serveEveryRequestSlowly(bothArrived);
        final var address = address();
        final var reply = new AtomicReference<ClusterMessage>();
        final var failure = new AtomicReference<Exception>();
        final var patientDone = new CountDownLatch(1);

        final var impatient = Thread.ofVirtual().start(
                () -> assertThrows(Exception.class, () -> pool.request(address, new ClusterMessage(), GIVE_UP_MS)));
        final var patient = Thread.ofVirtual().start(() -> {
            try {
                reply.set(pool.request(address, new ClusterMessage(), PATIENT_MS));
            } catch (Exception e) {
                failure.set(e);
            } finally {
                patientDone.countDown();
            }
        });

        assertTrue(bothArrived.await(5, TimeUnit.SECONDS), "both requests must reach the peer together");
        impatient.join();
        assertTrue(patientDone.await(10, TimeUnit.SECONDS), "the patient request never completed");
        patient.join();
        assertNotNull(reply.get(),
                () -> "a concurrent request must outlive another's timeout, but failed with " + failure.get());
        assertEquals(ClusterMessageType.GOSSIP_ACK, reply.get().getType());
    }

    @Test
    public void test_a_peer_that_cannot_be_connected_to_raises_the_transport_failure() throws Exception {
        final var unbound = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final var address = new NodeAddress(unbound.getInetAddress().getHostAddress(), unbound.getLocalPort());
        unbound.close();

        assertThrows(IOException.class, () -> pool.request(address, new ClusterMessage(), GIVE_UP_MS));
    }
}
