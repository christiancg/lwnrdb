package org.techhouse.unit.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.test.TestUtils;

public class AdminRecordPrimitivesTest {
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final Configuration config = Configuration.getInstance();
    private boolean origEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        origEnabled = config.isClusterEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.standardTearDown();
    }

    private static JsonObject definition() {
        final var definition = new JsonObject();
        definition.addProperty("name", "p");
        return definition;
    }

    @Test
    public void test_every_key_kind_round_trips_through_its_id() {
        for (final var key : new AdminRecordKey[]{AdminRecordKey.database("db"), AdminRecordKey.collection("db", "c"),
                AdminRecordKey.user("u"), AdminRecordKey.schema("db", "c"), AdminRecordKey.triggers("db", "c"),
                AdminRecordKey.procedure("db", "p"), AdminRecordKey.schedule("db", "s")}) {
            assertEquals(key, AdminRecordKey.parse(key.id()));
        }
        assertEquals("collection|db|c", AdminRecordKey.collection("db", "c").id());
    }

    @Test
    public void test_a_stamp_over_a_replaced_version_is_above_it() {
        final var future = clock.next() + 1_000_000L;

        assertTrue(AdminStamp.over(future) > future);
    }

    @Test
    public void test_a_stamped_definition_carries_its_version_and_leaves_the_original_alone() {
        final var original = definition();

        final var stamped = AdminStamp.stamped(original, 42L);

        assertEquals(42L, AdminStamp.versionOf(stamped));
        assertEquals(0L, AdminStamp.versionOf(original));
        assertEquals(0L, AdminStamp.versionOf(null));
        final var numeric = definition();
        numeric.addProperty("writeVersion", 42);
        assertEquals(0L, AdminStamp.versionOf(numeric));
    }

    @Test
    public void test_a_wrapped_schema_unwraps_to_itself_and_a_legacy_schema_is_read_as_is() {
        final var schema = new JsonObject();
        schema.addProperty("type", "object");

        final var wrapped = AdminStamp.wrappedSchema(schema, 7L);

        assertEquals(7L, AdminStamp.versionOf(wrapped));
        assertEquals(schema, AdminStamp.unwrappedSchema(wrapped));
        assertSame(schema, AdminStamp.unwrappedSchema(schema));
        assertNull(AdminStamp.unwrappedSchema(null));
    }

    @Test
    public void test_a_record_outranks_by_version_and_a_tombstone_wins_a_tie() {
        final var key = AdminRecordKey.user("u");
        final var live = new AdminRecord(key, 5L, definition());
        final var tombstone = AdminRecord.tombstone(key, 5L);

        assertTrue(live.outranks(null));
        assertTrue(new AdminRecord(key, 6L, definition()).outranks(live));
        assertFalse(new AdminRecord(key, 4L, definition()).outranks(live));
        assertTrue(tombstone.outranks(live));
        assertFalse(live.outranks(tombstone));
        assertFalse(live.outranks(new AdminRecord(key, 5L, definition())));
        assertEquals(0L, new AdminRecord().version());
    }

    @Test
    public void test_a_record_survives_the_wire() {
        final var record = new AdminRecord(AdminRecordKey.procedure("db", "p"), Long.MAX_VALUE, definition());

        final var received = eJson.fromJson(eJson.toJson(record), AdminRecord.class);

        assertEquals(record.key(), received.key());
        assertEquals(Long.MAX_VALUE, received.version());
        assertEquals(definition(), received.body());
        assertTrue(
                eJson.fromJson(eJson.toJson(AdminRecord.tombstone(record.key(), 1L)), AdminRecord.class).isTombstone());
    }

    @Test
    public void test_a_standalone_node_keeps_no_admin_tombstones() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);

        AdminTombstone.record(AdminRecordKey.user("u"));

        assertTrue(AdminTombstone.all().isEmpty());
    }

    @Test
    public void test_a_clustered_tombstone_is_stamped_by_the_clock_and_the_highest_one_wins() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        final var before = clock.current();

        AdminTombstone.record(AdminRecordKey.user("u"));
        AdminTombstone.recordAt(AdminRecordKey.user("u"), 3L);

        assertTrue(AdminTombstone.all().get(AdminRecordKey.user("u").id()) > before);
    }
}
