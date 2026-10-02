package org.techhouse.unit.ops.req;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.RequestParser;
import org.techhouse.ops.req.SaveProcedureRequest;
import org.techhouse.ops.req.SaveScheduleRequest;
import org.techhouse.ops.req.SaveTriggerRequest;

public class StampedFieldsOnTheWireTest {
    @Test
    public void test_a_trigger_request_parses_the_stamp_a_client_sends() {
        final var parsed = (SaveTriggerRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_TRIGGER\",\"databaseName\":\"db\",\"collectionName\":\"c\",\"name\":\"t\","
                        + "\"events\":[\"CREATED\"],\"procedureName\":\"p\",\"stampedVersion\":3,"
                        + "\"stampedDefiner\":\"admin\"}");

        assertEquals("admin", parsed.getStampedDefiner());
        assertEquals(3L, parsed.getStampedVersion());
        assertFalse(parsed.carriesCoordinatorStamp());
    }

    @Test
    public void test_a_schedule_request_parses_the_stamp_a_client_sends() {
        final var parsed = (SaveScheduleRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_SCHEDULE\",\"databaseName\":\"db\",\"name\":\"s\",\"procedureName\":\"p\","
                        + "\"intervalMs\":1000,\"stampedVersion\":3,\"stampedDefiner\":\"admin\"}");

        assertEquals("admin", parsed.getStampedDefiner());
        assertFalse(parsed.carriesCoordinatorStamp());
    }

    @Test
    public void test_only_a_replicated_request_carries_the_coordinator_stamp() {
        final var parsed = (SaveProcedureRequest) RequestParser.parseRequest(
                "{\"type\":\"SAVE_PROCEDURE\",\"databaseName\":\"db\",\"name\":\"p\",\"script\":\"return 1;\","
                        + "\"stampedVersion\":3}");

        assertFalse(parsed.carriesCoordinatorStamp());
        parsed.setReplicated(true);
        assertTrue(parsed.carriesCoordinatorStamp());
        parsed.setStampedVersion(0);
        assertFalse(parsed.carriesCoordinatorStamp());
    }
}
