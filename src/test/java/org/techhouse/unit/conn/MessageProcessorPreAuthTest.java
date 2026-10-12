package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.conn.PreAuthSlots;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MessageProcessorPreAuthTest {
    private static final String ADDRESS = "127.0.0.1";
    private static final String USERNAME = "preauth_admin";
    private static final String AUTHENTICATE = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + USERNAME
            + "\",\"password\":\"password123\"}";
    private static final String GARBAGE = "garbage";
    private final Configuration config = Configuration.getInstance();
    private final PreAuthSlots preAuthSlots = IocContainer.get(PreAuthSlots.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createAdmin(USERNAME);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedConnectionsPerAddress",
                ConfigKey.MAX_UNAUTHENTICATED_CONNECTIONS_PER_ADDRESS.defaultValue());
        TestUtils.setPrivateField(config, "maxUnauthenticatedInvalidRequests",
                ConfigKey.MAX_UNAUTHENTICATED_INVALID_REQUESTS.defaultValue());
        TestUtils.standardTearDown();
    }

    private static List<String> run(String... lines) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        final var thread = new Thread(new MessageProcessor(TestUtils.clientSocket(in, out)));
        thread.start();
        thread.join(10_000);
        assertFalse(thread.isAlive());
        final var written = out.toString(StandardCharsets.UTF_8);
        return written.isEmpty() ? List.of() : Arrays.asList(written.split("\\R"));
    }

    @Test
    public void test_an_address_past_its_unauthenticated_allowance_is_refused_with_503_14() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedConnectionsPerAddress", 1);
        assertTrue(preAuthSlots.acquire(ADDRESS));
        try {
            final var responses = run(AUTHENTICATE);
            assertEquals(1, responses.size(), responses.toString());
            assertTrue(responses.getFirst().contains("\"type\":\"CLOSE_CONNECTION\""), responses.getFirst());
            assertTrue(responses.getFirst().contains("503-14"), responses.getFirst());
        } finally {
            preAuthSlots.release(ADDRESS);
        }
        assertEquals(0, preAuthSlots.heldBy(ADDRESS));
    }

    @Test
    public void test_authenticating_frees_the_pre_auth_slot() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedConnectionsPerAddress", 1);
        final var responses = run(AUTHENTICATE, "{\"type\":\"LIST_DATABASES\"}");
        assertEquals(2, responses.size(), responses.toString());
        assertTrue(responses.get(1).contains("\"status\":\"OK\""), responses.get(1));
        assertEquals(0, preAuthSlots.heldBy(ADDRESS));
    }

    @Test
    public void test_unparseable_lines_before_authentication_close_the_connection_at_the_limit() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedInvalidRequests", 3);
        final var responses = run(GARBAGE, GARBAGE, GARBAGE, GARBAGE, AUTHENTICATE);
        assertEquals(3, responses.size(), responses.toString());
    }

    @Test
    public void test_unparseable_lines_after_authentication_never_close_the_connection() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedInvalidRequests", 2);
        final var responses = run(AUTHENTICATE, GARBAGE, GARBAGE, GARBAGE, "{\"type\":\"LIST_DATABASES\"}");
        assertEquals(5, responses.size(), responses.toString());
        assertTrue(responses.get(4).contains("\"status\":\"OK\""), responses.get(4));
    }

    @Test
    public void test_zero_disables_the_unparseable_line_cap() throws Exception {
        TestUtils.setPrivateField(config, "maxUnauthenticatedInvalidRequests", 0);
        final var responses = run(GARBAGE, GARBAGE, GARBAGE, GARBAGE, GARBAGE, GARBAGE, AUTHENTICATE);
        assertEquals(7, responses.size(), responses.toString());
    }

    @Test
    public void test_an_unauthenticated_request_is_answered_401_without_validation() throws Exception {
        final var responses = run(
                "{\"type\":\"SET_DATABASE_OWNERS\",\"databaseName\":\"anyDb\",\"owners\":[\"nobodyHere\"]}");
        assertEquals(1, responses.size(), responses.toString());
        assertTrue(responses.getFirst().contains("401-1"), responses.getFirst());
        assertFalse(responses.getFirst().contains("does not exist"), responses.getFirst());
    }
}
