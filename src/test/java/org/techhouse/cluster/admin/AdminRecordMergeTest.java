package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.test.TestGlobals;

public class AdminRecordMergeTest extends AdminRecordTestBase {

    @Test
    public void test_a_newer_record_installs_and_reports_a_change() {
        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "fresh", clock.next())), true);

        assertTrue(result.complete());
        assertTrue(result.changed());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "fresh"));
    }

    @Test
    public void test_an_older_record_than_the_local_one_is_skipped() {
        final var local = liveVersion(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL));

        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, TestGlobals.COLL, 2L, "f")), true);

        assertTrue(result.complete());
        assertFalse(result.changed());
        assertTrue(cache.getIndexesForCollection(TestGlobals.DB, TestGlobals.COLL).isEmpty());
        assertEquals(local, liveVersion(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL)));
    }

    @Test
    public void test_a_tombstone_at_the_live_version_removes_the_record_and_is_kept() throws Exception {
        final var version = liveVersion(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL));

        final var result = AdminRecordMerge
                .apply(List.of(tombstone(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL), version)), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertEquals(version,
                tombstones().get(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL).id()).longValue());
    }

    @Test
    public void test_a_live_record_at_a_tombstone_version_does_not_resurrect_it() throws Exception {
        final var key = AdminRecordKey.collection(TestGlobals.DB, "buried");
        final var version = clock.next();
        AdminTombstone.recordAt(key, version);

        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "buried", version)), true);

        assertFalse(result.changed());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "buried"));
    }

    @Test
    public void test_a_newer_live_record_outranks_an_older_tombstone() throws Exception {
        AdminTombstone.recordAt(AdminRecordKey.collection(TestGlobals.DB, "reborn"), clock.next());

        AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "reborn", clock.next())), true);

        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "reborn"));
    }

    @Test
    public void test_a_child_of_an_unknown_database_is_incomplete() {
        final var result = AdminRecordMerge.apply(List.of(collection("nodb", "coll", clock.next())), true);

        assertFalse(result.complete());
        assertNull(cache.getAdminCollectionEntry("nodb", "coll"));
    }

    @Test
    public void test_a_child_older_than_its_database_tombstone_is_dropped_as_complete() throws Exception {
        final var childVersion = clock.next();
        AdminTombstone.recordAt(AdminRecordKey.database("gonedb"), clock.next());

        final var result = AdminRecordMerge.apply(List.of(collection("gonedb", "coll", childVersion)), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminCollectionEntry("gonedb", "coll"));
    }

    @Test
    public void test_a_schema_under_a_dropped_collection_is_dropped_as_complete() throws Exception {
        final var schemaVersion = clock.next();
        AdminTombstone.recordAt(AdminRecordKey.collection(TestGlobals.DB, "gonecoll"), clock.next());

        final var result = AdminRecordMerge.apply(List.of(schema("gonecoll", schemaVersion)), true);

        assertTrue(result.complete());
        assertNull(fs.readCollectionSchema(TestGlobals.DB, "gonecoll"));
    }

    @Test
    public void test_a_schema_under_an_unknown_collection_is_incomplete() {
        assertFalse(AdminRecordMerge.apply(List.of(schema("nocoll", clock.next())), true).complete());
    }

    @Test
    public void test_records_apply_parents_first_whatever_order_they_arrive_in() {
        final var dbVersion = clock.next();
        final var schema = new AdminRecord(AdminRecordKey.schema("newdb", "child"), clock.next(),
                schema("child", 1L).body());
        final var result = AdminRecordMerge
                .apply(List.of(schema, collection("newdb", "child", clock.next()), database("newdb", dbVersion)), true);

        assertTrue(result.complete());
        assertNotNull(cache.getAdminDbEntry("newdb"));
        assertNotNull(cache.getAdminCollectionEntry("newdb", "child"));
    }

    @Test
    public void test_a_user_tombstone_deletes_the_user_and_its_ownerships() throws Exception {
        AdminRecordMerge.apply(List.of(user("dave", clock.next(), Map.of(TestGlobals.DB, PermissionLevel.READ))), true);
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(TestGlobals.DB,
                cache.getAdminDbEntry(TestGlobals.DB).getCollections(), List.of("dave")));

        final var result = AdminRecordMerge.apply(List.of(tombstone(AdminRecordKey.user("dave"), clock.next())), true);

        assertTrue(result.complete());
        assertNull(cache.getAdminUserEntry("dave"));
        assertFalse(cache.getAdminDbEntry(TestGlobals.DB).isOwner("dave"));
    }

    @Test
    public void test_a_tombstone_for_a_record_never_seen_is_kept() throws Exception {
        final var version = clock.next();

        final var result = AdminRecordMerge.apply(List.of(tombstone(AdminRecordKey.user("ghost"), version)), true);

        assertTrue(result.complete());
        assertEquals(version, tombstones().get(AdminRecordKey.user("ghost").id()).longValue());
    }

    @Test
    public void test_an_installed_user_keeps_the_record_version() {
        final var version = clock.next();

        AdminRecordMerge.apply(List.of(user("erin", version)), true);

        assertEquals("hash-erin", cache.getAdminUserEntry("erin").getPasswordHash());
        assertEquals(version, AdminOperationHelper.versionOf(cache.getPkIndexAdminUserEntry("erin")));
    }

    @Test
    public void test_unreadable_tombstones_merge_nothing() {
        final var file = new File(TestGlobals.PATH + Globals.FILE_SEPARATOR + Globals.CLUSTER_FOLDER
                + Globals.FILE_SEPARATOR + Globals.CLUSTER_ADMIN_TOMBSTONES_FILE);
        assertTrue(file.mkdirs());

        final var result = AdminRecordMerge.apply(List.of(collection(TestGlobals.DB, "blocked", clock.next())), true);

        assertFalse(result.complete());
        assertFalse(result.changed());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "blocked"));
    }

    @Test
    public void test_a_record_that_fails_to_apply_leaves_the_merge_incomplete_but_applies_the_rest() {
        final var broken = new AdminRecord(AdminRecordKey.user("broken"), clock.next(), null) {
            @Override
            public boolean isTombstone() {
                throw new IllegalStateException("boom");
            }
        };

        final var result = AdminRecordMerge.apply(List.of(broken, user("fine", clock.next())), true);

        assertFalse(result.complete());
        assertTrue(result.changed());
        assertNotNull(cache.getAdminUserEntry("fine"));
    }

    @Test
    public void test_an_interrupted_merge_stops_incomplete() {
        Thread.currentThread().interrupt();
        try {
            final var result = AdminRecordMerge.apply(List.of(database("intdb", clock.next())), true);
            assertFalse(result.complete());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }
}
