package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.test.TestUtils;

public class MessageProcessorRequestLimitTest {
    private static final String ADMIN = "limit_admin";
    private static final String LIST_DATABASES = "{\"type\":\"LIST_DATABASES\"}";

    private final Configuration config = Configuration.getInstance();
    private ScriptedConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        TestUtils.setPrivateField(config, "maxRequestSize", "128Kb");
        connection = new ScriptedConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        TestUtils.setPrivateField(config, "maxRequestSize", ConfigKey.MAX_REQUEST_SIZE.defaultValue());
        TestUtils.standardTearDown();
    }

    private static String authenticate() {
        return "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"password123\"}";
    }

    private static String paddedListDatabases(int bytes) {
        final var prefix = "{\"type\":\"LIST_DATABASES\",\"pad\":\"";
        return prefix + "a".repeat(bytes - prefix.length() - 2) + "\"}";
    }

    private static String flatArrayListDatabases(int bytes) {
        final var prefix = "{\"type\":\"LIST_DATABASES\",\"x\":[1";
        return prefix + ",1".repeat((bytes - prefix.length() - 2) / 2) + "]}";
    }

    private void startAuthenticated() throws InterruptedException {
        connection.start();
        assertTrue(connection.exchange(authenticate()).contains("\"OK\""));
    }

    @Test
    public void test_an_oversized_line_is_answered_400_22_and_closed() throws Exception {
        startAuthenticated();

        connection.sendBytes("a".repeat(200_000).getBytes(StandardCharsets.UTF_8));
        final var refusal = connection.response();

        assertTrue(refusal.contains("400-22") && refusal.contains("CLOSE_CONNECTION"), refusal);
        assertTrue(connection.awaitClosed(5000));
    }

    @Test
    public void test_an_oversized_line_before_authentication_uses_the_64kb_limit() throws Exception {
        connection.start();

        final var refusal = connection.exchange(paddedListDatabases(70_000));

        assertTrue(refusal.contains("400-22"), refusal);
        assertTrue(connection.awaitClosed(5000));
    }

    @Test
    public void test_an_authenticated_line_between_64kb_and_the_limit_is_served() throws Exception {
        startAuthenticated();

        final var served = connection.exchange(paddedListDatabases(100_000));

        assertTrue(served.contains("\"OK\""), served);
        assertFalse(connection.awaitClosed(10));
    }

    @Test
    public void test_a_flat_array_over_the_token_budget_is_answered_as_an_invalid_command_and_the_connection_continues()
            throws Exception {
        startAuthenticated();

        final var refused = connection.exchange(flatArrayListDatabases(100_000));

        assertTrue(refused.contains("The command is not valid"), refused);
        assertTrue(connection.exchange(LIST_DATABASES).contains("\"OK\""));
        assertTrue(connection.exchange(flatArrayListDatabases(1_000)).contains("\"OK\""),
                "a small array stays within the budget");
    }

    @Test
    public void test_a_request_over_the_limit_never_reaches_the_parser() throws Exception {
        final var processor = mock(OperationProcessor.class);
        TestUtils.setPrivateField(connection.processor(), "operationProcessor", processor);
        connection.start();

        connection.send(paddedListDatabases(70_000) + "\n" + LIST_DATABASES);

        assertTrue(connection.response().contains("400-22"));
        assertTrue(connection.awaitClosed(5000));
        assertNull(connection.responseWithin(100), "the line after the refused one is never read");
        verifyNoInteractions(processor);
    }

    @Test
    public void test_an_out_of_memory_error_closes_the_connection_and_cleans_up() throws Exception {
        final var processor = mock(OperationProcessor.class);
        when(processor.processMessage(any(), any())).thenThrow(new OutOfMemoryError("scripted"));
        TestUtils.setPrivateField(connection.processor(), "operationProcessor", processor);
        connection.start();
        final var clientId = connection.clientId();

        final var answer = connection.exchange(authenticate());

        assertTrue(answer.contains("500-8"), answer);
        assertTrue(connection.awaitClosed(5000));
        assertFalse(IocContainer.get(ClientTracker.class).clientIdsSnapshot().contains(clientId));
    }
}
