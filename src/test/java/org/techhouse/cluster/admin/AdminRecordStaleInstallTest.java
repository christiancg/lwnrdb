package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.test.TestGlobals;

public class AdminRecordStaleInstallTest extends AdminRecordTestBase {
    private static final String NAME = "clustered";

    static AdminRecord schedule(String name, long version) {
        final var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("procedureName", "proc");
        body.addProperty("intervalMs", 1000);
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.schedule(TestGlobals.DB, name), version, body);
    }

    private AdminRecord triggers(long version) {
        final var body = new JsonObject();
        body.add("triggers", new JsonArray());
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.triggers(TestGlobals.DB, TestGlobals.COLL), version, body);
    }

    private AdminRecord installThenDelete(AdminRecord saved) {
        AdminRecordMerge.apply(List.of(saved), true);
        final var deleted = tombstone(saved.key(), clock.next());
        assertTrue(AdminRecordMerge.apply(List.of(deleted), true).complete());
        return deleted;
    }

    @Test
    public void test_a_save_installed_after_its_newer_delete_does_not_resurrect_the_schedule() throws Exception {
        final var saved = schedule(NAME, clock.next());
        final var deleted = installThenDelete(saved);

        DefinitionRecordApplier.install(saved);

        AdminRecordMerge.apply(List.of(deleted), false);
        assertNull(StoredDefinitions.schedule(TestGlobals.DB, NAME),
                "the older save must not leave a schedule file behind its newer tombstone");
    }

    @Test
    public void test_a_stale_procedure_schema_and_triggers_install_is_skipped() throws Exception {
        final var procedure = procedure(TestGlobals.DB, "stale", clock.next());
        final var schema = schema(TestGlobals.COLL, clock.next());
        final var triggers = triggers(clock.next());
        installThenDelete(procedure);
        installThenDelete(schema);
        installThenDelete(triggers);

        assertTrue(DefinitionRecordApplier.install(procedure));
        assertTrue(DefinitionRecordApplier.install(schema));
        assertTrue(DefinitionRecordApplier.install(triggers));

        assertNull(StoredDefinitions.procedure(TestGlobals.DB, "stale"));
        assertNull(StoredDefinitions.schema(TestGlobals.DB, TestGlobals.COLL));
        assertNull(StoredDefinitions.triggers(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_stale_collection_install_after_its_drop_is_skipped() throws Exception {
        final var saved = collection(TestGlobals.DB, "stale_coll", clock.next());
        installThenDelete(saved);

        assertTrue(CollectionRecordApplier.install(saved));

        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "stale_coll"));
    }

    @Test
    public void test_a_stale_database_install_after_its_drop_is_skipped() throws Exception {
        final var saved = database("stale_db", clock.next());
        installThenDelete(saved);

        assertTrue(DatabaseRecordApplier.install(saved));

        assertNull(cache.getAdminDbEntry("stale_db"));
    }

    @Test
    public void test_a_stale_user_install_after_its_delete_is_skipped() throws Exception {
        final var saved = user("ivy", clock.next());
        installThenDelete(saved);

        assertTrue(UserRecordApplier.install(saved));

        assertNull(cache.getAdminUserEntry("ivy"));
    }

    @Test
    public void test_a_tombstone_older_than_the_live_record_is_kept_and_removes_nothing() throws Exception {
        final var older = clock.next();
        final var saved = schedule(NAME, clock.next());
        AdminRecordMerge.apply(List.of(saved), true);

        assertTrue(DefinitionRecordApplier.remove(tombstone(saved.key(), older)));

        assertNotNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
        assertEquals(older, tombstones().get(saved.key().id()).longValue());
    }

    @Test
    public void test_a_newer_live_record_wins_over_an_install_decided_earlier() throws Exception {
        final var older = schedule(NAME, clock.next());
        final var newer = schedule(NAME, clock.next());
        AdminRecordMerge.apply(List.of(newer), true);

        assertTrue(DefinitionRecordApplier.install(older));

        assertEquals(newer.version(), liveVersion(newer.key()));
    }
}
