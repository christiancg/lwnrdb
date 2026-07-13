package org.techhouse.unit.cluster.msg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.ejson.elements.JsonObject;

public class AdminSnapshotPayloadTest {
    @Test
    public void test_all_args_constructor_null_coerces_lists() {
        final var payload = new AdminSnapshotPayload(4L, null, null, null);
        assertEquals(4L, payload.getEpoch());
        assertTrue(payload.getDatabases().isEmpty());
        assertTrue(payload.getCollections().isEmpty());
        assertTrue(payload.getUsers().isEmpty());
    }

    @Test
    public void test_no_arg_constructor_defaults() {
        final var payload = new AdminSnapshotPayload();
        assertEquals(0L, payload.getEpoch());
        assertTrue(payload.getDatabases().isEmpty());
        assertTrue(payload.getCollections().isEmpty());
        assertTrue(payload.getUsers().isEmpty());
    }

    @Test
    public void test_setters() {
        final var payload = new AdminSnapshotPayload();
        payload.setEpoch(9L);
        payload.setDatabases(List.of(new JsonObject()));
        payload.setCollections(List.of(new JsonObject(), new JsonObject()));
        payload.setUsers(List.of(new JsonObject()));
        assertEquals(9L, payload.getEpoch());
        assertEquals(1, payload.getDatabases().size());
        assertEquals(2, payload.getCollections().size());
        assertEquals(1, payload.getUsers().size());
    }

    @Test
    public void test_setters_null_coerce_to_empty() {
        final var payload = new AdminSnapshotPayload(1L, List.of(new JsonObject()), List.of(new JsonObject()),
                List.of(new JsonObject()));
        payload.setDatabases(null);
        payload.setCollections(null);
        payload.setUsers(null);
        assertTrue(payload.getDatabases().isEmpty());
        assertTrue(payload.getCollections().isEmpty());
        assertTrue(payload.getUsers().isEmpty());
    }
}
