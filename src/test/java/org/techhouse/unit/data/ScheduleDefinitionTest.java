package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;

public class ScheduleDefinitionTest {
    private static ScheduleDefinition sample() {
        final var args = new JsonObject();
        args.add("days", new JsonNumber(1));
        return new ScheduleDefinition("nightlyRollup", "rollup", "0 3 * * *", 0L, args, 60000L, true, "ops",
                "daily order rollup", 3L, 1756000000000L, 1756600000000L, "ops");
    }

    @Test
    public void test_round_trips_through_json() {
        final var original = sample();
        final var restored = ScheduleDefinition.fromJsonObject(original.toJsonObject());
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
        assertEquals("nightlyRollup", restored.getName());
        assertEquals("rollup", restored.getProcedureName());
        assertEquals("0 3 * * *", restored.getCron());
        assertEquals(0L, restored.getIntervalMs());
        assertEquals(60000L, restored.getTimeoutMs());
        assertEquals("ops", restored.getDefiner());
        assertEquals("daily order rollup", restored.getDescription());
        assertEquals(3L, restored.getVersion());
        assertEquals(1756000000000L, restored.getCreatedAt());
        assertEquals(1756600000000L, restored.getUpdatedAt());
        assertEquals("ops", restored.getUpdatedBy());
        assertEquals(1, restored.getArgs().get("days").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_interval_schedule_round_trips_without_a_cron() {
        final var definition = new ScheduleDefinition("s", "p", null, 2000L, null, 0L, true, "ops", null, 1L, 1L, 1L,
                "ops");
        final var json = definition.toJsonObject();
        assertFalse(json.has("cron"));
        final var restored = ScheduleDefinition.fromJsonObject(json);
        assertNull(restored.getCron());
        assertEquals(2000L, restored.getIntervalMs());
    }

    @Test
    public void test_absent_enabled_reads_as_true() {
        final var json = sample().toJsonObject();
        json.remove("enabled");
        assertTrue(ScheduleDefinition.fromJsonObject(json).isEnabled());
    }

    @Test
    public void test_absent_timeout_reads_as_zero() {
        final var json = sample().toJsonObject();
        json.remove("timeoutMs");
        assertEquals(0L, ScheduleDefinition.fromJsonObject(json).getTimeoutMs());
    }

    @Test
    public void test_absent_optional_fields_read_as_defaults() {
        final var json = new JsonObject();
        json.addProperty("name", "s");
        json.addProperty("procedureName", "p");
        final var restored = ScheduleDefinition.fromJsonObject(json);
        assertEquals(0L, restored.getIntervalMs());
        assertEquals(0L, restored.getVersion());
        assertNull(restored.getDefiner());
        assertNull(restored.getDescription());
        assertNull(restored.getUpdatedBy());
        assertTrue(restored.getArgs().isEmpty());
    }

    @Test
    public void test_summary_omits_args() {
        final var summary = sample().toSummaryJson();
        assertFalse(summary.has("args"));
        assertTrue(summary.has("name"));
        assertTrue(summary.has("procedureName"));
    }

    @Test
    public void test_equality_and_to_string() {
        final var one = sample();
        assertNotEquals(new Object(), one);
        assertNotEquals(new ScheduleDefinition("other", "rollup", "0 3 * * *", 0L, null, 60000L, true, "ops", null, 3L,
                1L, 1L, "ops"), one);
        assertTrue(one.toString().contains("nightlyRollup"));
        assertTrue(new ScheduleDefinition().getArgs().isEmpty());
    }
}
