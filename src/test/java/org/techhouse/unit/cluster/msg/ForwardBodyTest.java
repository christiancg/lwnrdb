package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.ForwardBody;

public class ForwardBodyTest {

    @Test
    public void test_round_trips_json_with_quotes_and_braces() {
        final var json = "{\"type\":\"SAVE\",\"object\":{\"_id\":\"a\",\"name\":\"o'brien \\\"jr\\\"\"}}";
        final var encoded = ForwardBody.encode(json);
        assertNotEquals(json, encoded);
        assertEquals(json, ForwardBody.decode(encoded));
    }

    @Test
    public void test_round_trips_unicode() {
        final var json = "{\"msg\":\"café ü 你好\"}";
        assertEquals(json, ForwardBody.decode(ForwardBody.encode(json)));
    }
}
