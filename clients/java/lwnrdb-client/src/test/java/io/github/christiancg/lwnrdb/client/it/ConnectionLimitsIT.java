package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.error.ServerClosedConnectionException;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ConnectionLimitsIT extends ServerIT {
    @Override
    protected Map<String, String> config() {
        return Map.of("maxRequestSize", "128Kb", "maxEntrySize", "64Kb");
    }

    @Test
    public void test_an_oversized_request_closes_its_connection_and_the_pool_recovers() {
        client.database("limits_it").create();
        final var notes = client.database("limits_it").collection("notes");
        notes.create();
        final var error = assertThrows(ServerClosedConnectionException.class,
                () -> notes.save(Document.of("_id", "big", "text", "x".repeat(200 * 1024))));
        assertEquals("400-22", error.rawCode().orElseThrow());
        notes.save(Document.of("_id", "small", "text", "ok"));
        assertTrue(notes.findById("small").isPresent());
    }
}
