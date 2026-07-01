package org.techhouse.unit.ops.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.resp.ListenResponse;

public class ListenResponseTest {

    @Test
    public void constructor_initialListen_setsFields() {
        final var results = List.<JsonObject>of();
        final var resp = new ListenResponse("some-id", results, "abc123", false);

        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals("Listening for changes", resp.getMessage());
        assertEquals("some-id", resp.getListenId());
        assertSame(results, resp.getResults());
        assertEquals("abc123", resp.getResultHash());
        assertFalse(resp.isUpdate());
    }

    @Test
    public void constructor_updateListen_setsFields() {
        final var results = List.<JsonObject>of();
        final var resp = new ListenResponse("some-id", results, "abc123", true);

        assertEquals(OperationType.LISTEN, resp.getType());
        assertEquals(OperationStatus.OK, resp.getStatus());
        assertEquals("Query results updated", resp.getMessage());
        assertEquals("some-id", resp.getListenId());
        assertSame(results, resp.getResults());
        assertEquals("abc123", resp.getResultHash());
        assertTrue(resp.isUpdate());
    }

    @Test
    public void setters_updateFields() {
        final var resp = new ListenResponse("id", List.of(), "hash", false);
        final var newResults = List.of(new JsonObject());

        resp.setListenId("new-id");
        resp.setResults(newResults);
        resp.setResultHash("new-hash");
        resp.setUpdate(true);

        assertEquals("new-id", resp.getListenId());
        assertSame(newResults, resp.getResults());
        assertEquals("new-hash", resp.getResultHash());
        assertTrue(resp.isUpdate());
    }
}
