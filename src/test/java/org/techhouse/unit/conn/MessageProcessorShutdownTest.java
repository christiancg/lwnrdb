package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestUtils;

public class MessageProcessorShutdownTest {
    private static final String SHUTTING_DOWN = "503-13";

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static Socket socket(InputStream in, OutputStream out) throws Exception {
        final var socket = mock(Socket.class);
        final var address = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(address);
        when(address.getHostAddress()).thenReturn("127.0.0.1");
        when(socket.getInputStream()).thenReturn(in);
        when(socket.getOutputStream()).thenReturn(out);
        return socket;
    }

    private static String run(String message, BooleanSupplier shuttingDown) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream((message + "\n").getBytes(StandardCharsets.UTF_8));
        final var thread = new Thread(new MessageProcessor(socket(in, out), shuttingDown));
        thread.start();
        thread.join(3000);
        assertFalse(thread.isAlive());
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void test_a_write_is_refused_once_the_server_is_stopping() throws Exception {
        final var response = run(
                "{\"type\":\"SAVE\",\"databaseName\":\"testDb\",\"collectionName\":\"testColl\"," + "\"object\":{}}",
                () -> true);

        assertTrue(response.contains(SHUTTING_DOWN), response);
    }

    @Test
    public void test_a_public_operation_is_refused_once_the_server_is_stopping() throws Exception {
        assertTrue(run("{\"type\":\"LIST_DATABASES\"}", () -> true).contains(SHUTTING_DOWN));
    }

    @Test
    public void test_a_refused_request_never_reaches_the_operation_processor() throws Exception {
        final var processor = mock(OperationProcessor.class);
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream("{\"type\":\"LIST_DATABASES\"}\n".getBytes(StandardCharsets.UTF_8));
        final var messageProcessor = new MessageProcessor(socket(in, out), () -> true);
        TestUtils.setPrivateField(messageProcessor, "operationProcessor", processor);

        final var thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);

        verifyNoInteractions(processor);
    }

    @Test
    public void test_a_rollback_is_still_answered_once_the_server_is_stopping() throws Exception {
        final var response = run("{\"type\":\"ROLLBACK_TRANSACTION\"}", () -> true);

        assertFalse(response.contains(SHUTTING_DOWN), response);
    }

    @Test
    public void test_closing_the_connection_is_still_answered_once_the_server_is_stopping() throws Exception {
        final var response = run("{\"type\":\"CLOSE_CONNECTION\"}", () -> true);

        assertTrue(response.contains("CLOSE_CONNECTION"), response);
        assertFalse(response.contains(SHUTTING_DOWN), response);
    }

    @Test
    public void test_requests_are_served_while_the_server_is_running() throws Exception {
        final var response = run("{\"type\":\"LIST_DATABASES\"}", () -> false);

        assertTrue(response.contains("OK"), response);
        assertFalse(response.contains(SHUTTING_DOWN), response);
    }

    @Test
    public void test_a_runtime_failure_answers_an_error_and_keeps_the_connection() throws Exception {
        final var processor = mock(OperationProcessor.class);
        when(processor.processMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("corrupt metadata"))
                .thenReturn(OperationResponse.ok(OperationType.CLOSE_CONNECTION, "closed"));
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream(
                "{\"type\":\"LIST_DATABASES\"}\n{\"type\":\"CLOSE_CONNECTION\"}\n".getBytes(StandardCharsets.UTF_8));
        final var messageProcessor = new MessageProcessor(socket(in, out));
        TestUtils.setPrivateField(messageProcessor, "operationProcessor", processor);

        final var thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);

        final var lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertTrue(lines.getFirst().contains("500-8"), lines.toString());
        assertTrue(lines.size() >= 2, "the loop must keep reading after a runtime failure: " + lines);
    }

    @Test
    public void test_a_request_is_in_flight_only_while_it_runs() throws Exception {
        final var inFlight = org.techhouse.ioc.IocContainer.get(org.techhouse.conn.InFlightRequests.class);
        final var seenWhileRunning = new java.util.concurrent.atomic.AtomicInteger(-1);
        final var processor = mock(OperationProcessor.class);
        when(processor.processMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(_ -> {
                    seenWhileRunning.set(inFlight.current());
                    return OperationResponse.ok(OperationType.LIST_DATABASES, "listed");
                });
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream("{\"type\":\"LIST_DATABASES\"}\n".getBytes(StandardCharsets.UTF_8));
        final var messageProcessor = new MessageProcessor(socket(in, out));
        TestUtils.setPrivateField(messageProcessor, "operationProcessor", processor);

        final var thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);

        assertTrue(seenWhileRunning.get() >= 1);
        org.junit.jupiter.api.Assertions.assertEquals(0, inFlight.current());
    }

    @Test
    public void test_a_failing_request_still_leaves_the_in_flight_count() throws Exception {
        final var inFlight = org.techhouse.ioc.IocContainer.get(org.techhouse.conn.InFlightRequests.class);
        final var processor = mock(OperationProcessor.class);
        when(processor.processMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream("{\"type\":\"LIST_DATABASES\"}\n".getBytes(StandardCharsets.UTF_8));
        final var messageProcessor = new MessageProcessor(socket(in, out));
        TestUtils.setPrivateField(messageProcessor, "operationProcessor", processor);

        final var thread = new Thread(messageProcessor);
        thread.start();
        thread.join(3000);

        org.junit.jupiter.api.Assertions.assertEquals(0, inFlight.current());
    }
}
