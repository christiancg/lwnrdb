package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.conn.SocketServer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.ProcedureOperationHelper;
import org.techhouse.ops.ScriptAdmission;
import org.techhouse.ops.UserOperationHelper;
import org.techhouse.ops.req.CreateUserRequest;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The cap end-to-end: a real listening {@link SocketServer}, real TCP connections, and the whole
 * accept -> MessageProcessor -> OperationProcessor -> ScriptOperationHelper path. The helper-level tests
 * drive one entry point on the calling thread; only here does each caller arrive on its own connection and
 * its own virtual thread, which is the shape of the concurrency the cap exists to bound.
 */
public class ScriptAdmissionE2ETest {
    private static final String ADMIN = "e2eadmin";
    private static final String PASSWORD = "password123";
    private static final String SLOW_SCRIPT = "export default new Promise(r => setTimeout(() => r(1), 400));";
    private static final Configuration configuration = Configuration.getInstance();
    private static final ScriptAdmission admission = IocContainer.get(ScriptAdmission.class);
    private static SocketServer server;
    private static Thread serverThread;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        final var request = new CreateUserRequest();
        request.setUsername(ADMIN);
        request.setPassword(PASSWORD);
        request.setAdmin(true);
        request.setGlobalPermissions(new HashSet<>());
        request.setDatabasePermissions(new HashMap<>());
        request.setCollectionPermissions(new HashMap<>());
        UserOperationHelper.processCreateUser(request);
        port = freePort();
        server = new SocketServer(port);
        serverThread = new Thread(server::serve, "e2e-server");
        serverThread.setDaemon(true);
        serverThread.start();
        awaitListening();
    }

    @AfterAll
    static void stopServer() throws Exception {
        server.stopAccepting();
        serverThread.join(10_000L);
        TestUtils.setPrivateField(configuration, "scriptsEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptsEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptInstructionBudget", 10_000_000L);
        TestUtils.setPrivateField(configuration, "scriptTimeoutMs", 5_000L);
        TestUtils.setPrivateField(configuration, "scriptMaxDepth", 200);
        TestUtils.setPrivateField(configuration, "scriptMaxSourceBytes", 262_144L);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLines", 1_000);
        TestUtils.setPrivateField(configuration, "scriptMaxLogLineChars", 4_096);
        TestUtils.setPrivateField(configuration, "scriptMaxMemoryBytes", 67_108_864L);
        TestUtils.setPrivateField(configuration, "scriptMaxResultBytes", 16_777_216L);
        TestUtils.setPrivateField(configuration, "procedureCacheSize", 128);
    }

    @AfterEach
    void restoreAdmission() {
        admission.reconfigure(0, 0L);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // Polling is the only option here: serve() binds the socket on the server thread and offers no
    // readiness signal, so there is nothing to await on. The delay runs between every attempt, not
    // only after a refused one, which is what keeps this a poll rather than a spin.
    private static void awaitListening() throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (canConnect()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20L);
        }
        throw new IllegalStateException("the e2e server never started listening on port " + port);
    }

    private static boolean canConnect() {
        try (var probe = new Socket("127.0.0.1", port)) {
            return probe.isConnected();
        } catch (IOException notListeningYet) {
            return false;
        }
    }

    private static String runScript(String script) {
        return "{\"type\":\"RUN_SCRIPT\",\"databaseName\":\"" + TestGlobals.DB + "\",\"script\":\"" + script + "\"}";
    }

    // Every caller arrives on its own connection, released together so the burst really overlaps.
    private static List<String> burst(String request) throws Exception {
        final var responses = new ConcurrentLinkedQueue<String>();
        final var ready = new CountDownLatch(6);
        final var go = new CountDownLatch(1);
        final var done = new CountDownLatch(6);
        for (var i = 0; i < 6; i++) {
            Thread.ofVirtual().start(() -> {
                try (var client = new Client()) {
                    client.authenticate();
                    ready.countDown();
                    go.await();
                    responses.add(client.send(request));
                } catch (Exception e) {
                    responses.add("client failed: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS), "not every caller authenticated");
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "the burst never finished");
        return List.copyOf(responses);
    }

    private static List<String> okAnd(List<String> responses, List<String> rejected) {
        final var ok = new ArrayList<String>();
        for (final var response : responses) {
            if (response.contains("\"status\":\"OK\"")) {
                ok.add(response);
            } else if (response.contains("\"errorCode\":\"" + ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode() + "\"")) {
                rejected.add(response);
            }
        }
        assertEquals(responses.size(), ok.size() + rejected.size(),
                "a response was neither OK nor the capacity rejection: " + responses);
        return ok;
    }

    // The headline behaviour, over the wire: the node admits at most its capacity and turns the rest away
    // with 503-6 rather than queueing them into the heap.
    @Test
    public void test_concurrent_wire_clients_are_capped_and_rejected_cleanly() throws Exception {
        admission.reconfigure(2, 0L);
        final var rejected = new ArrayList<String>();
        final var ok = okAnd(burst(runScript(SLOW_SCRIPT)), rejected);
        assertTrue(!ok.isEmpty() && ok.size() <= 2, "at most the capacity may run with no wait, got " + ok.size());
        assertEquals(6 - ok.size(), rejected.size());
        for (final var response : ok) {
            assertTrue(response.contains("\"result\":1"), response);
        }
        for (final var response : rejected) {
            assertTrue(response.contains("\"type\":\"RUN_SCRIPT\""), response);
            assertTrue(response.contains("\"status\":\"ERROR\""), response);
        }
        assertEquals(2, admission.available(), "a permit leaked");
        assertEquals(rejected.size(), admission.getRejected());
    }

    // The same burst with room to queue: the cap still bounds concurrency, but no caller is turned away.
    @Test
    public void test_burst_is_absorbed_when_callers_may_wait() throws Exception {
        admission.reconfigure(2, 10_000L);
        final var rejected = new ArrayList<String>();
        final var ok = okAnd(burst(runScript(SLOW_SCRIPT)), rejected);
        assertEquals(6, ok.size(), "the wait should have absorbed the whole burst: " + rejected);
        assertEquals(0, admission.getRejected());
        assertTrue(admission.getWaited() > 0, "no caller queued, so the wait was never exercised");
        assertEquals(2, admission.available());
    }

    // The off switch, over the wire: with the cap disabled the burst behaves exactly as it did before the
    // feature existed.
    @Test
    public void test_cap_disabled_allows_every_caller() throws Exception {
        admission.reconfigure(0, 0L);
        final var rejected = new ArrayList<String>();
        final var ok = okAnd(burst(runScript(SLOW_SCRIPT)), rejected);
        assertEquals(6, ok.size());
        assertTrue(rejected.isEmpty());
        assertEquals(0, admission.capacity());
    }

    @Test
    public void test_call_procedure_is_capped_over_the_wire() throws Exception {
        ProcedureOperationHelper.executeSave(new SaveProcedureRequest(TestGlobals.DB, "e2eslow", "return 7;"), ADMIN);
        admission.reconfigure(1, 0L);
        assertTrue(admission.tryAcquire());
        try (var client = new Client()) {
            client.authenticate();
            final var response = client.send("{\"type\":\"CALL_PROCEDURE\",\"databaseName\":\"" + TestGlobals.DB
                    + "\",\"procedureName\":\"e2eslow\"}");
            assertTrue(response.contains("\"type\":\"CALL_PROCEDURE\""), response);
            assertTrue(response.contains("\"errorCode\":\"" + ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode() + "\""),
                    response);
        } finally {
            admission.release();
        }
    }

    // Observable only end-to-end: a rejection is an ordinary error response, so the connection stays usable
    // and the client's retry succeeds once a permit frees. A caller that had to reconnect after every 503-6
    // would turn a capacity blip into a connection storm.
    @Test
    public void test_a_rejected_connection_stays_usable_and_the_retry_succeeds() throws Exception {
        admission.reconfigure(1, 0L);
        try (var client = new Client()) {
            client.authenticate();
            assertTrue(admission.tryAcquire());
            final var refused = client.send(runScript("return 1;"));
            assertTrue(refused.contains("\"errorCode\":\"" + ErrorCode.SCRIPT_CONCURRENCY_LIMIT.getCode() + "\""),
                    refused);
            admission.release();
            final var retried = client.send(runScript("return 41 + 1;"));
            assertTrue(retried.contains("\"status\":\"OK\""), retried);
            assertTrue(retried.contains("\"result\":42"), retried);
        }
    }

    // The operator's window onto the cap, read the way an operator reads it: over a connection, after the
    // rejections have happened.
    @Test
    public void test_stats_over_the_wire_report_the_admission_state() throws Exception {
        admission.reconfigure(2, 0L);
        final var rejected = new ArrayList<String>();
        okAnd(burst(runScript(SLOW_SCRIPT)), rejected);
        try (var client = new Client()) {
            client.authenticate();
            final var stats = client.send("{\"type\":\"GET_DATABASE_STATS\"}");
            assertTrue(stats.contains("\"capacity\":2"), stats);
            assertTrue(stats.contains("\"available\":2"), stats);
            assertTrue(stats.contains("\"rejected\":" + rejected.size()), stats);
            assertTrue(stats.contains("\"waited\":0"), stats);
        }
    }

    // One client connection: authenticate once, then exchange request/response lines.
    private static final class Client implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private Client() throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(60_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String send(String request) throws IOException {
            writer.write(request);
            writer.newLine();
            writer.flush();
            final var response = reader.readLine();
            if (response == null) {
                throw new IOException("the server closed the connection without responding to " + request);
            }
            return response;
        }

        private void authenticate() throws IOException {
            final var response = send(
                    "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"" + PASSWORD + "\"}");
            if (!response.contains("\"status\":\"OK\"")) {
                throw new IOException("authentication failed: " + response);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
