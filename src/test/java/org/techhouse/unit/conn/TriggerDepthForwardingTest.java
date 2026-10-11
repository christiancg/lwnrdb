package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ops.req.RequestParser;

public class TriggerDepthForwardingTest {
    private static String sanitize(String rawMessage) throws Exception {
        final Method method = MessageProcessor.class.getDeclaredMethod("withoutTriggerDepth", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, rawMessage);
    }

    @Test
    public void test_a_client_supplied_triggerDepth_does_not_survive_forwarding() throws Exception {
        final var raw = "{\"type\":\"SAVE\",\"databaseName\":\"db\",\"collectionName\":\"coll\","
                + "\"triggerDepth\":-2000000000,\"object\":{\"_id\":\"a\"}}";
        final var forwarded = sanitize(raw);
        assertFalse(forwarded.contains("triggerDepth"),
                "the forwarded body is re-parsed on the owner, so the depth must not travel in it");
        assertEquals(0, RequestParser.parseRequest(forwarded).getTriggerDepth());
    }

    @Test
    public void test_a_body_without_a_depth_is_returned_unchanged() throws Exception {
        final var raw = "{\"type\":\"SAVE\",\"databaseName\":\"db\",\"collectionName\":\"coll\","
                + "\"object\":{\"_id\":\"a\"}}";
        assertEquals(raw, sanitize(raw));
    }

    @Test
    public void test_the_rest_of_the_body_survives_sanitisation() throws Exception {
        final var raw = "{\"type\":\"SAVE\",\"databaseName\":\"db\",\"collectionName\":\"coll\","
                + "\"triggerDepth\":5,\"object\":{\"_id\":\"a\",\"value\":42}}";
        final var parsed = RequestParser.parseRequest(sanitize(raw));
        assertEquals("db", parsed.getDatabaseName());
        assertEquals("coll", parsed.getCollectionName());
    }

    @Test
    public void test_a_malformed_body_is_passed_through_untouched() throws Exception {
        final var raw = "{\"triggerDepth\":";
        assertEquals(raw, sanitize(raw));
    }
}
