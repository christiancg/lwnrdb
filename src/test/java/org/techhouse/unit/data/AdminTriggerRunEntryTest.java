package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class AdminTriggerRunEntryTest {

    private static AdminTriggerRunEntry entry(String runId, long chunkSeq, EventType type, List<String> ids,
            List<JsonObject> documents) {
        return new AdminTriggerRunEntry(runId, chunkSeq, "node-1", "myDb", "myColl", "audit", "recalc", type, false,
                "alice", 2, 1234L, ids, documents);
    }

    private static JsonObject document() {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("gone"));
        return object;
    }

    @Test
    public void test_id_is_the_run_id_and_chunk_sequence() {
        assertEquals("run|3", AdminTriggerRunEntry.buildId("run", 3L));
        assertEquals("run", AdminTriggerRunEntry.runIdOf("run|3"));
        assertEquals("run", entry("run", 0L, EventType.CREATED, List.of("a"), List.of()).getRunId());
        assertEquals("run|0", entry("run", 0L, EventType.CREATED, List.of("a"), List.of()).get_id());
    }

    // A record id with no separator is not a chunked id, so the whole string is the run id.
    @Test
    public void test_run_id_of_an_unseparated_id_is_the_id_itself() {
        assertEquals("plain", AdminTriggerRunEntry.runIdOf("plain"));
    }

    @Test
    public void test_getters_report_what_was_constructed() {
        final var record = entry("run", 1L, EventType.UPDATED, List.of("a", "b"), List.of());
        assertEquals("node-1", record.getNodeId());
        assertEquals("myDb", record.getDbName());
        assertEquals("myColl", record.getCollName());
        assertEquals("audit", record.getTriggerName());
        assertEquals("recalc", record.getProcedureName());
        assertEquals(EventType.UPDATED, record.getEventType());
        assertFalse(record.isBatchMode());
        assertEquals("alice", record.getActingUser());
        assertEquals(2, record.getDepth());
        assertEquals(1234L, record.getFiredAt());
        assertEquals(List.of("a", "b"), record.getIds());
        assertTrue(record.getDocuments().isEmpty());
        assertEquals(Globals.ADMIN_DB_NAME, record.getDatabaseName());
        assertEquals(Globals.ADMIN_TRIGGER_RUNS_COLLECTION_NAME, record.getCollectionName());
    }

    @Test
    public void test_null_id_and_document_lists_become_empty() {
        final var record = entry("run", 0L, EventType.CREATED, null, null);
        assertTrue(record.getIds().isEmpty());
        assertTrue(record.getDocuments().isEmpty());
    }

    @Test
    public void test_round_trips_an_id_carrying_record() {
        final var original = entry("run", 0L, EventType.CREATED, List.of("a", "b"), List.of());
        final var data = original.getData();
        data.addProperty(Globals.PK_FIELD, original.get_id());

        final var parsed = AdminTriggerRunEntry.fromJsonObject(data);

        assertEquals(original, parsed);
        assertEquals(original.hashCode(), parsed.hashCode());
        assertEquals(List.of("a", "b"), parsed.getIds());
    }

    // A DELETED run carries the documents themselves, because they no longer exist to be re-read.
    @Test
    public void test_round_trips_a_document_carrying_record() {
        final var original = entry("run", 2L, EventType.DELETED, List.of(), List.of(document()));
        final var data = original.getData();
        data.addProperty(Globals.PK_FIELD, original.get_id());

        final var parsed = AdminTriggerRunEntry.fromJsonObject(data);

        assertEquals(EventType.DELETED, parsed.getEventType());
        assertEquals(1, parsed.getDocuments().size());
        assertEquals(original, parsed);
    }

    // An unauthenticated write has no acting user, so the field legitimately round-trips as JSON null.
    @Test
    public void test_a_null_acting_user_round_trips() {
        final var original = new AdminTriggerRunEntry("run", 0L, "node-1", "myDb", "myColl", "audit", "recalc",
                EventType.CREATED, true, null, 0, 1L, List.of("a"), List.of());
        final var data = original.getData();
        data.addProperty(Globals.PK_FIELD, original.get_id());

        final var parsed = AdminTriggerRunEntry.fromJsonObject(data);

        assertNull(parsed.getActingUser());
        assertTrue(parsed.isBatchMode());
    }

    @Test
    public void test_equality_distinguishes_records() {
        final var base = entry("run", 0L, EventType.CREATED, List.of("a"), List.of());
        assertNotEquals(base, entry("other", 0L, EventType.CREATED, List.of("a"), List.of()));
        assertNotEquals(base, entry("run", 0L, EventType.UPDATED, List.of("a"), List.of()));
        assertNotEquals(base, entry("run", 0L, EventType.CREATED, List.of("b"), List.of()));
        assertNotEquals("not an entry", base);
        assertNotEquals(null, base);
    }

    @Test
    public void test_to_string_names_the_run_and_its_size() {
        final var text = entry("run", 0L, EventType.CREATED, List.of("a", "b"), List.of()).toString();
        assertTrue(text.contains("runId=run"));
        assertTrue(text.contains("triggerName=audit"));
        assertTrue(text.contains("ids=2"));
    }
}
