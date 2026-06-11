package org.techhouse.unit.ops.req;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.SetDatabaseOwnersRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SetDatabaseOwnersRequestTest {
    @Test
    public void test_operation_type() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        assertEquals(OperationType.SET_DATABASE_OWNERS, req.getType());
        assertEquals("mydb", req.getDatabaseName());
    }

    @Test
    public void test_get_owners_empty_by_default() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        assertTrue(req.getOwners().isEmpty());
    }

    @Test
    public void test_set_and_get_owners() {
        final var req = new SetDatabaseOwnersRequest("mydb");
        req.setOwners(List.of("alice", "bob"));
        assertEquals(List.of("alice", "bob"), req.getOwners());
    }

    @Test
    public void test_parser_parses_set_database_owners() {
        final var msg = "{\"type\":\"SET_DATABASE_OWNERS\",\"databaseName\":\"mydb\",\"owners\":[\"alice\",\"bob\"]}";
        final var req = (SetDatabaseOwnersRequest) org.techhouse.ops.req.RequestParser.parseRequest(msg);
        assertEquals(OperationType.SET_DATABASE_OWNERS, req.getType());
        assertEquals("mydb", req.getDatabaseName());
        assertEquals(List.of("alice", "bob"), req.getOwners());
    }
}
