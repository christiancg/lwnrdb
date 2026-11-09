package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ClientException;
import org.techhouse.client.ClientOptions;
import org.techhouse.client.Connection;
import org.techhouse.client.Dump;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.unit.client.support.FakeLineServer;
import org.techhouse.unit.client.support.FakeSource;

public class DumpPagingTest {
    private static final int DOCUMENTS = 20;
    private static final int PADDING = 200;
    private static final int REPLY_CAP = 2048;
    private static final int LARGE_PADDING = 4 * REPLY_CAP;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private FakeLineServer server;
    private FakeSource source;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeLineServer();
        source = new FakeSource().database("shop", "orders").documents("shop", "orders", DOCUMENTS, PADDING);
        source.serveOn(server);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.close();
    }

    private int dump(int replyCap) throws IOException {
        final var options = ClientOptions.parse(new String[]{"--dump"}, Map.of());
        try (var connection = new Connection(new Socket(InetAddress.getLoopbackAddress(), server.port()), Duration.ZERO,
                Duration.ofSeconds(10), replyCap)) {
            return new Dump(connection, options, stdout, new PrintStream(stderr, true, StandardCharsets.UTF_8)).run();
        }
    }

    private static int intStep(JsonObject request, int index, String field) {
        return request.get("aggregationSteps").asJsonArray().get(index).asJsonObject().get(field).asJsonNumber()
                .getValue().intValue();
    }

    private List<int[]> pageRequests() {
        return server.requests().stream().filter(request -> "AGGREGATE".equals(Connection.stringOf(request, "type")))
                .map(request -> new int[]{intStep(request, 0, "skip"), intStep(request, 1, "limit")}).toList();
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void aTooLongPageIsHalvedAndRetriedAtTheSameSkip() throws IOException {
        assertEquals(ClientException.OK, dump(REPLY_CAP));

        final var pages = pageRequests();
        assertEquals(Dump.PAGE_SIZE, pages.getFirst()[1]);
        assertEquals(0, pages.get(1)[0], "the halved page asks for the same position again");
        assertEquals(Dump.PAGE_SIZE / 2, pages.get(1)[1]);
        assertTrue(err().contains("shop.orders: " + DOCUMENTS + " documents"), err());
    }

    @Test
    public void aPageRefusedWith503_17AfterItsRetriesIsHalved() throws IOException {
        server.respond("AGGREGATE",
                request -> intStep(request, 1, "limit") > 5
                        ? FakeLineServer.error("AGGREGATE", "ERROR", "503-17")
                        : source.page(request));

        assertEquals(ClientException.OK, dump(Connection.MAX_REPLY_BYTES));

        assertTrue(pageRequests().stream().anyMatch(page -> page[1] <= 5), "the page shrank until it was answered");
        assertTrue(err().contains("shop.orders: " + DOCUMENTS + " documents"), err());
    }

    @Test
    public void aSingleDocumentOverTheReplyCapFailsWithItsPosition() {
        new FakeSource().database("shop", "orders").documents("shop", "orders", 3, LARGE_PADDING).serveOn(server);

        final var failure = assertThrows(ClientException.class, () -> dump(REPLY_CAP));

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("shop.orders at position 0"), failure.getMessage());
    }

    @Test
    public void aReplyOfTheWrongShapeIsAClientException() {
        server.reply("LIST_DATABASES", FakeLineServer.ok("LIST_DATABASES", "\"databases\":[1,2]"));

        final var failure = assertThrows(ClientException.class, () -> dump(Connection.MAX_REPLY_BYTES));

        assertEquals(ClientException.FAILED, failure.exitCode());
        assertTrue(failure.getMessage().contains("unexpected databases"), failure.getMessage());
    }
}
