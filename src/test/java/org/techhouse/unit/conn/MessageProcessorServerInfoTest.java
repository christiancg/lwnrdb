package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MessageProcessorServerInfoTest {
    private static final String SERVER_INFO = "{\"type\":\"SERVER_INFO\"}";
    private static final String ADMIN = "server_info_wire_admin";
    private static final String AUTHENTICATE = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN
            + "\",\"password\":\"password123\"}";
    private static final EJson eJson = IocContainer.get(EJson.class);

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.standardTearDown();
    }

    private static List<JsonObject> run(MessageProcessor processor, ByteArrayOutputStream out) throws Exception {
        final var thread = new Thread(processor);
        thread.start();
        thread.join(3000);
        assertFalse(thread.isAlive());
        return out.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                .map(line -> eJson.fromJson(line, JsonObject.class)).toList();
    }

    private static List<JsonObject> run(String messages, BooleanSupplier shuttingDown) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream((messages + "\n").getBytes(StandardCharsets.UTF_8));
        return run(new MessageProcessor(TestUtils.clientSocket(in, out), shuttingDown), out);
    }

    private static String text(JsonObject object, String field) {
        return object.get(field).asJsonString().getValue();
    }

    private static JsonObject serverOf(JsonObject reply) {
        return reply.get("server").asJsonObject();
    }

    @Test
    public void test_answers_before_authentication_with_the_state_only() throws Exception {
        final var replies = run(SERVER_INFO + "\n" + SERVER_INFO, () -> false);

        assertEquals(2, replies.size());
        for (final var reply : replies) {
            assertEquals("SERVER_INFO", text(reply, "type"));
            assertEquals("OK", text(reply, "status"));
            assertEquals("READY", text(serverOf(reply), "state"));
            assertFalse(serverOf(reply).has("version"));
        }
    }

    @Test
    public void test_answers_shutting_down_while_other_operations_are_refused() throws Exception {
        final var replies = run(SERVER_INFO + "\n{\"type\":\"LIST_DATABASES\"}", () -> true);

        assertEquals("SHUTTING_DOWN", text(serverOf(replies.get(0)), "state"));
        assertEquals("503-13", text(replies.get(1), "errorCode"));
    }

    @Test
    public void test_an_authenticated_caller_sees_the_build() throws Exception {
        TestUtils.createAdmin(ADMIN);

        final var replies = run(AUTHENTICATE + "\n" + SERVER_INFO, () -> false);

        final var server = serverOf(replies.get(1));
        assertTrue(server.has("version"));
        assertTrue(server.has("node"));
    }

    @Test
    public void test_answers_inside_a_transaction() throws Exception {
        TestUtils.createAdmin(ADMIN);

        final var replies = run(AUTHENTICATE + "\n{\"type\":\"START_TRANSACTION\"}\n" + SERVER_INFO
                + "\n{\"type\":\"ROLLBACK_TRANSACTION\"}", () -> false);

        assertEquals("OK", text(replies.get(2), "status"));
        assertEquals("READY", text(serverOf(replies.get(2)), "state"));
        assertEquals("OK", text(replies.get(3), "status"));
    }

    @Test
    public void test_is_never_forwarded() throws Exception {
        TestUtils.createAdmin(ADMIN);
        final var router = mock(ClusterRouter.class);
        final var out = new ByteArrayOutputStream();
        final var in = new ByteArrayInputStream(
                (AUTHENTICATE + "\n" + SERVER_INFO + "\n").getBytes(StandardCharsets.UTF_8));
        final var processor = new MessageProcessor(TestUtils.clientSocket(in, out), () -> false);
        TestUtils.setPrivateField(processor, "clusterRouter", router);

        final var replies = run(processor, out);

        assertEquals("OK", text(replies.get(1), "status"));
        verifyNoInteractions(router);
    }
}
