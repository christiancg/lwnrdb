package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.conn.RequestMetrics;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorMetricsTest {
    private static final String ADMIN = "metrics_wire_admin";
    private static final String AUTHENTICATE = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN
            + "\",\"password\":\"password123\"}";
    private static final String SAVE = "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"object\":{\"_id\":\"m1\",\"n\":1}}";
    private static final String MISSING = "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"_id\":\"absent\"}";
    private static final EJson eJson = IocContainer.get(EJson.class);
    private final Configuration configuration = Configuration.getInstance();
    private RequestMetrics metrics;
    private int originalMaxConnections;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        metrics = new RequestMetrics();
        originalMaxConnections = configuration.getMaxConnections();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "maxConnections", originalMaxConnections);
        TestUtils.standardTearDown();
    }

    private MessageProcessor processor(String messages, ByteArrayOutputStream out) throws Exception {
        final var in = new ByteArrayInputStream((messages + "\n").getBytes(StandardCharsets.UTF_8));
        final var processor = new MessageProcessor(TestUtils.clientSocket(in, out));
        TestUtils.setPrivateField(processor, "requestMetrics", metrics);
        return processor;
    }

    private List<JsonObject> run(String messages) throws Exception {
        final var out = new ByteArrayOutputStream();
        run(processor(messages, out));
        return out.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank())
                .map(line -> eJson.fromJson(line, JsonObject.class)).toList();
    }

    private static void run(MessageProcessor processor) throws InterruptedException {
        final var thread = new Thread(processor);
        thread.start();
        thread.join(5000);
        assertFalse(thread.isAlive());
    }

    private Map<String, Long> counts() {
        final var counts = new HashMap<String, Long>();
        metrics.forEachCount(
                (operation, status, code, count) -> counts.put(operation + "/" + status + "/" + code, count));
        return counts;
    }

    private long durationCount(String operation) {
        final var found = new long[1];
        metrics.forEachDuration((name, histogram) -> {
            if (name.equals(operation)) {
                found[0] = histogram.count();
            }
        });
        return found[0];
    }

    private static String codeOf(JsonObject reply) {
        return reply.get("errorCode").asJsonString().getValue();
    }

    @Test
    public void test_each_answered_request_is_counted_with_its_status_or_code() throws Exception {
        final var replies = run(AUTHENTICATE + "\n" + SAVE + "\n" + SAVE + "\n" + MISSING);

        final var notFound = codeOf(replies.get(3));
        assertEquals(Map.of("AUTHENTICATE/OK/", 1L, "SAVE/OK/", 2L, "FIND_BY_ID/NOT_FOUND/" + notFound, 1L), counts());
        assertEquals(2L, durationCount("SAVE"));
        assertEquals(1L, durationCount("FIND_BY_ID"));
    }

    @Test
    public void test_an_unparseable_line_counts_as_unknown() throws Exception {
        final var replies = run(AUTHENTICATE + "\nthis is not json");

        assertEquals(Map.of("AUTHENTICATE/OK/", 1L, "UNKNOWN/ERROR/" + codeOf(replies.get(1)), 1L), counts());
        assertEquals(1L, durationCount(RequestMetrics.UNKNOWN_OPERATION));
    }

    @Test
    public void test_an_unauthenticated_request_counts_its_refusal() throws Exception {
        run("{\"type\":\"LIST_DATABASES\"}");

        assertEquals(Map.of("LIST_DATABASES/UNAUTHENTICATED/401-1", 1L), counts());
    }

    @Test
    public void test_a_connection_over_max_connections_counts_a_refusal() throws Exception {
        TestUtils.setPrivateField(configuration, "maxConnections", 1);
        processor("", new ByteArrayOutputStream());

        run(processor(SAVE, new ByteArrayOutputStream()));

        assertEquals(1L, metrics.refusedCount(RequestMetrics.RefusalReason.MAX_CONNECTIONS));
        assertEquals(0L, metrics.refusedCount(RequestMetrics.RefusalReason.UNAUTHENTICATED_PER_ADDRESS));
        assertEquals(Map.of(), counts());
    }
}
