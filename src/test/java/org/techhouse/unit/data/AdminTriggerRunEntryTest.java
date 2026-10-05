package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

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

    private static AdminTriggerRunEntry reread(AdminTriggerRunEntry record) {
        final var eJson = IocContainer.get(EJson.class);
        final var data = record.getData();
        data.addProperty(Globals.PK_FIELD, record.get_id());
        return AdminTriggerRunEntry.fromJsonObject(eJson.fromJson(eJson.toJson(data), JsonObject.class));
    }

    private static JsonObject documentWithId(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        return object;
    }

    @Test
    public void test_a_custom_shaped_last_error_is_stored_as_plain_text_and_reads_back() {
        final var record = entry("run", 0L, EventType.CREATED, List.of("a"), List.of());
        record.markAttempt(TriggerRunStatus.DEAD, 1, "#abc(: x)", 0L);

        assertEquals("\\#abc(: x)", record.getLastError());
        assertEquals("\\#abc(: x)", reread(record).getLastError(), "the stored record must stay readable");
    }

    @Test
    public void test_prior_versions_round_trip_as_text_above_two_to_the_53() {
        final var record = entry("run", 0L, EventType.UPDATED, List.of("a", "b"), List.of());
        final var hybridClockValue = (1L << 57) + 1L;
        record.stage(Map.of("a", hybridClockValue, "b", AdminTriggerRunEntry.ABSENT_VERSION));

        final var read = reread(record);

        assertEquals(TriggerRunStatus.STAGED, read.getStatus());
        assertEquals(hybridClockValue, read.getPriorVersions().get("a"));
        assertEquals(AdminTriggerRunEntry.ABSENT_VERSION, read.getPriorVersions().get("b"));
    }

    @Test
    public void test_mark_attempt_leaves_staged_and_drops_prior_versions() {
        final var record = entry("run", 0L, EventType.UPDATED, List.of("a"), List.of());
        record.stage(Map.of("a", 7L));

        record.markAttempt(TriggerRunStatus.PENDING, 1, "boom", 10L);

        assertEquals(TriggerRunStatus.PENDING, record.getStatus());
        assertTrue(record.getPriorVersions().isEmpty());
        assertTrue(reread(record).getPriorVersions().isEmpty());
    }

    @Test
    public void test_narrow_to_keeps_only_landed_ids() {
        final var record = entry("run", 0L, EventType.CREATED, List.of("a", "b"), List.of());
        record.stage(Map.of("a", -1L, "b", -1L));

        record.narrowTo(Set.of("a"));

        assertEquals(List.of("a"), record.getIds());
        assertEquals(TriggerRunStatus.PENDING, record.getStatus());
        assertTrue(record.getPriorVersions().isEmpty());
        assertFalse(record.isEmpty());
    }

    @Test
    public void test_narrow_to_keeps_only_landed_documents() {
        final var record = entry("run", 0L, EventType.DELETED, List.of(),
                List.of(documentWithId("gone"), documentWithId("kept")));
        record.stage(Map.of("gone", 3L, "kept", 4L));

        record.narrowTo(Set.of("gone"));

        assertEquals(1, record.getDocuments().size());
        assertEquals("gone", record.getDocuments().getFirst().get(Globals.PK_FIELD).asJsonString().getValue());
        record.narrowTo(Set.of());
        assertTrue(record.isEmpty());
    }

    @Test
    public void test_a_record_without_prior_versions_reads_as_empty() {
        final var read = reread(entry("run", 0L, EventType.CREATED, List.of("a"), List.of()));

        assertTrue(read.getPriorVersions().isEmpty());
        assertEquals(TriggerRunStatus.PENDING, read.getStatus());
        assertEquals(TriggerRunStatus.PENDING, TriggerRunStatus.STAGED.reported());
        assertEquals(TriggerRunStatus.DEAD, TriggerRunStatus.DEAD.reported());
    }
}
