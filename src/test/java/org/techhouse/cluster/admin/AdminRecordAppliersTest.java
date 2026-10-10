package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminRecordAppliersTest extends AdminRecordTestBase {

    @Test
    public void test_a_database_record_installs_its_owners_and_keeps_the_local_collections() {
        AdminRecordMerge.apply(List.of(database(TestGlobals.DB, clock.next(), "alice")), true);

        final var installed = cache.getAdminDbEntry(TestGlobals.DB);
        assertTrue(installed.isOwner("alice"));
        assertTrue(cache.getCollectionNamesForDatabase(TestGlobals.DB).contains(TestGlobals.COLL));
    }

    @Test
    public void test_a_newer_case_variant_database_replaces_the_local_one() throws Exception {
        final var version = clock.next();

        final var result = AdminRecordMerge.apply(List.of(database("MYDB", version)), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNotNull(cache.getAdminDbEntry("MYDB"));
        assertEquals(version, tombstones().get(AdminRecordKey.database(TestGlobals.DB).id()).longValue());
    }

    @Test
    public void test_an_older_case_variant_database_is_buried_under_the_local_one() throws Exception {
        final var local = liveVersion(AdminRecordKey.database(TestGlobals.DB));

        final var result = AdminRecordMerge.apply(List.of(database("MYDB", 2L)), true);

        assertTrue(result.complete());
        assertNotNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNull(cache.getAdminDbEntry("MYDB"));
        assertEquals(local, tombstones().get(AdminRecordKey.database("MYDB").id()).longValue());
    }

    @Test
    public void test_a_database_tombstone_from_anti_entropy_quarantines_it() {
        final var result = AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.database(TestGlobals.DB), clock.next())), false);

        assertTrue(result.complete());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_live_database_tombstone_drops_it() {
        final var result = AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.database(TestGlobals.DB), clock.next())), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
    }

    @Test
    public void test_a_collection_tombstone_from_anti_entropy_quarantines_it() {
        final var result = AdminRecordMerge.apply(
                List.of(tombstone(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL), clock.next())), false);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_collection_record_builds_and_drops_indexes_to_match() throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL, Set.of("old")));

        AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, TestGlobals.COLL, clock.next(), "added")), true);

        assertEquals(Set.of("added"), cache.getIndexesForCollection(TestGlobals.DB, TestGlobals.COLL));
        assertFalse(fs.indexBuildMarkers().isMarked(TestGlobals.DB, TestGlobals.COLL, "added"));
    }

    @Test
    public void test_a_collection_record_carries_its_incarnation_and_the_clock_observes_it() {
        final var incarnation = clock.next() + 1_000_000L;

        AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "incarnated", clock.next(), incarnation)), true);

        assertEquals(incarnation, cache.getAdminCollectionEntry(TestGlobals.DB, "incarnated").getIncarnation());
        assertTrue(clock.current() >= incarnation);
    }

    @Test
    public void test_a_newer_case_variant_collection_replaces_the_local_one() throws Exception {
        final var version = clock.next();

        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "MYCOLLECTION", version)), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "MYCOLLECTION"));
        assertEquals(version,
                tombstones().get(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL).id()).longValue());
    }

    @Test
    public void test_an_older_case_variant_collection_is_buried_under_the_local_one() throws Exception {
        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "MYCOLLECTION", 2L)), true);

        assertTrue(result.complete());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "MYCOLLECTION"));
        assertTrue(tombstones().containsKey(AdminRecordKey.collection(TestGlobals.DB, "MYCOLLECTION").id()));
    }

    @Test
    public void test_a_procedure_record_installs_and_its_tombstone_removes_it() throws Exception {
        final var version = clock.next();

        AdminRecordMerge.apply(List.of(procedure(TestGlobals.DB, "proc", version)), true);
        assertEquals(version, liveVersion(AdminRecordKey.procedure(TestGlobals.DB, "proc")));

        AdminRecordMerge.apply(List.of(tombstone(AdminRecordKey.procedure(TestGlobals.DB, "proc"), clock.next())),
                true);
        assertNull(StoredDefinitions.procedure(TestGlobals.DB, "proc"));
    }

    @Test
    public void test_a_schema_record_installs_wrapped_and_its_tombstone_removes_it() throws Exception {
        final var version = clock.next();

        AdminRecordMerge.apply(List.of(schema(TestGlobals.COLL, version)), true);
        assertEquals(version, liveVersion(AdminRecordKey.schema(TestGlobals.DB, TestGlobals.COLL)));
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));

        AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.schema(TestGlobals.DB, TestGlobals.COLL), clock.next())), true);
        assertNull(StoredDefinitions.schema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_triggers_and_schedule_records_install_and_remove() throws Exception {
        final var triggers = AdminRecords.live(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL));
        assertNull(triggers);
        final var triggersBody = new JsonObject();
        triggersBody.add("triggers", new JsonArray());
        final var scheduleBody = new JsonObject();
        scheduleBody.addProperty("name", "nightly");
        scheduleBody.addProperty("procedureName", "proc");
        scheduleBody.addProperty("intervalMs", 60_000);

        final var result = AdminRecordMerge.apply(List.of(
                new AdminRecord(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL), clock.next(), triggersBody),
                new AdminRecord(AdminRecordKey.schedule(TestGlobals.DB, "nightly"), clock.next(), scheduleBody)), true);

        assertTrue(result.complete());
        assertNotNull(StoredDefinitions.triggers(TestGlobals.DB, TestGlobals.COLL));
        assertNotNull(StoredDefinitions.schedule(TestGlobals.DB, "nightly"));

        AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL), clock.next()),
                        tombstone(AdminRecordKey.schedule(TestGlobals.DB, "nightly"), clock.next())), true);
        assertNull(StoredDefinitions.triggers(TestGlobals.DB, TestGlobals.COLL));
        assertNull(StoredDefinitions.schedule(TestGlobals.DB, "nightly"));
    }

    @Test
    public void test_a_definition_whose_lock_stays_held_is_retried_later() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var holder = Thread.ofVirtual().start(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.join();
        final var origTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 50L);
        try {
            final var result = AdminRecordMerge.apply(List.of(schema(TestGlobals.COLL, clock.next())), true);

            assertFalse(result.complete());
            assertNull(StoredDefinitions.schema(TestGlobals.DB, TestGlobals.COLL));
        } finally {
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origTimeout);
        }
    }

    @Test
    public void test_a_removed_database_that_is_not_there_is_already_removed() throws Exception {
        assertTrue(DatabaseRecordApplier.remove("absent", true));
        assertTrue(CollectionRecordApplier.remove(TestGlobals.DB, "absent", false));
    }
}
