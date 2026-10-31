package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.ResponseBudget;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.test.Await;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorResponseBudgetTest {
    private static final String ADMIN = "budget_admin";
    private static final long LIMIT = 1024L * 1024;
    private static final int LARGE_FIELD_CHARS = (int) Globals.RESPONSE_BUDGET_FLOOR_BYTES + 1000;
    private static final String TARGET = "\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
            + TestGlobals.COLL + "\"";
    private final Configuration config = Configuration.getInstance();
    private final ResponseBudget budget = IocContainer.get(ResponseBudget.class);
    private ScriptedConnection connection;
    private long heldByTest;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        TestUtils.setPrivateField(config, "maxUnsentResponseBytes", String.valueOf(LIMIT));
        connection = new ScriptedConnection();
        connection.start();
        assertOk(connection
                .exchange("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"password123\"}"));
        assertOk(connection.exchange("{\"type\":\"SAVE\"," + TARGET + ",\"object\":{\"_id\":\"large\",\"blob\":\""
                + "l".repeat(LARGE_FIELD_CHARS) + "\"}}"));
        assertOk(connection.exchange("{\"type\":\"SAVE\"," + TARGET + ",\"object\":{\"_id\":\"small\"}}"));
        assertTrue(Await.until(() -> budget.unsentBytes() == 0, 10_000),
                "a connection of this JVM still holds " + budget.unsentBytes() + " response bytes");
    }

    @AfterEach
    void tearDown() throws Exception {
        budget.release(heldByTest);
        connection.resumeResponses();
        connection.close();
        TestUtils.setPrivateField(config, "maxUnsentResponseBytes", ConfigKey.MAX_UNSENT_RESPONSE_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static void assertOk(String response) {
        assertTrue(response.contains("\"OK\""), response);
    }

    private static void assertRefused(String response) {
        assertTrue(response.contains(ErrorCode.RESPONSE_BUDGET_EXHAUSTED.getCode()), response);
    }

    private void exhaustTheBudget() {
        heldByTest = LIMIT;
        budget.reserve(heldByTest);
    }

    private static String findById(String id) {
        return "{\"type\":\"FIND_BY_ID\"," + TARGET + ",\"_id\":\"" + id + "\"}";
    }

    @Test
    public void aLargeReadPastTheBudgetIsAnswered503_17AndCanBeRetried() throws Exception {
        exhaustTheBudget();

        final var refused = connection.exchange(findById("large"));
        assertRefused(refused);
        assertTrue(refused.contains("\"type\":\"FIND_BY_ID\""), refused);
        assertRefused(connection.exchange("{\"type\":\"AGGREGATE\"," + TARGET + ",\"aggregationSteps\":[]}"));

        budget.release(heldByTest);
        heldByTest = 0;
        assertOk(connection.exchange(findById("large")));
        assertTrue(eventually(() -> budget.unsentBytes() == 0), "every written response gave its bytes back");
    }

    @Test
    public void aSmallReadIsAnsweredWhateverTheBudget() throws Exception {
        exhaustTheBudget();

        assertOk(connection.exchange(findById("small")));
        assertOk(connection.exchange("{\"type\":\"LIST_DATABASES\"}"));
    }

    @Test
    public void aWriteOrAListenIsNeverRefused() throws Exception {
        exhaustTheBudget();

        assertOk(connection.exchange("{\"type\":\"SAVE\"," + TARGET + ",\"object\":{\"_id\":\"large-2\",\"blob\":\""
                + "m".repeat(LARGE_FIELD_CHARS) + "\"}}"));
        final var listen = connection.exchange("{\"type\":\"LISTEN\"," + TARGET + ",\"aggregationSteps\":[]}");
        assertOk(listen);
        assertFalse(listen.contains(ErrorCode.RESPONSE_BUDGET_EXHAUSTED.getCode()), "a large LISTEN answer is kept");
        assertTrue(listen.length() > 2 * LARGE_FIELD_CHARS, "the LISTEN answer carries both large documents");
    }

    @Test
    public void unreadResponsesHoldTheirBytesUntilTheConnectionEnds() throws Exception {
        connection.stallResponses();
        connection.send(findById("large"));
        assertTrue(connection.awaitBlockedWriter());
        assertTrue(budget.unsentBytes() > LARGE_FIELD_CHARS, "the unread response stays reserved");

        connection.close();

        assertTrue(eventually(() -> budget.unsentBytes() == 0), "the closed connection released its bytes");
    }

    @SuppressWarnings("BusyWait")
    private static boolean eventually(java.util.function.BooleanSupplier check) throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return check.getAsBoolean();
    }

    @Test
    public void theRefusalIsTheSameShapeAsTheOther503s() throws Exception {
        exhaustTheBudget();

        final var refused = connection.exchange(findById("large"));

        assertEquals(
                "{\"type\":\"FIND_BY_ID\",\"status\":\"ERROR\",\"message\":\""
                        + ErrorCode.RESPONSE_BUDGET_EXHAUSTED.getDefaultMessage() + "\",\"errorCode\":\"503-17\"}",
                refused);
    }
}
