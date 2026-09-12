package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

// Lives in org.techhouse.cluster rather than unit/cluster because AdminSnapshotConformer is package-private.
public class AdminSnapshotConformerTest {
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    private final Cache cache = IocContainer.get(Cache.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static AdminSnapshotPayload snapshot(List<JsonObject> dbs, List<JsonObject> colls, List<JsonObject> users) {
        return snapshot(dbs, colls, users, new JsonObject());
    }

    private static AdminSnapshotPayload snapshot(List<JsonObject> dbs, List<JsonObject> colls, List<JsonObject> users,
            JsonObject schemas) {
        return new AdminSnapshotPayload(5L, dbs, colls, users, schemas);
    }

    private static JsonObject dbJson(String name, List<String> owners) {
        return new AdminDbEntry(name, new ArrayList<>(), new ArrayList<>(owners)).getData();
    }

    private static JsonObject collJson(String db, String coll, Set<String> indexes) {
        final var json = new AdminCollEntry(db, coll, new HashSet<>(indexes)).getData().deepCopy();
        json.addProperty(Globals.PK_FIELD, Cache.getCollectionIdentifier(db, coll));
        return json;
    }

    private static void deleteRecursively(File file) {
        final var children = file.listFiles();
        if (children != null) {
            for (final var child : children) {
                deleteRecursively(child);
            }
        }
        assertTrue(file.delete());
    }

    private static JsonObject userJson() {
        return new AdminUserEntry("alice", "hash-alice", false, Set.of(), Map.of(), Map.of()).getData();
    }

    @Test
    public void test_conform_creates_missing_database_and_collection() throws Exception {
        conformer.conform(snapshot(List.of(dbJson("newdb", List.of("alice"))),
                List.of(collJson("newdb", "newcoll", Set.of())), List.of()));

        final var dbEntry = cache.getAdminDbEntry("newdb");
        assertNotNull(dbEntry);
        assertTrue(dbEntry.getOwners().contains("alice"));
        assertNotNull(cache.getAdminCollectionEntry("newdb", "newcoll"));
        assertTrue(cache.getCollectionNamesForDatabase("newdb").contains("newcoll"));
    }

    @Test
    public void test_conform_recreates_a_directory_that_went_missing_under_an_existing_admin_entry() throws Exception {
        final var snapshot = snapshot(List.of(dbJson("driftdb", List.of())),
                List.of(collJson("driftdb", "driftcoll", Set.of())), List.of());
        conformer.conform(snapshot);
        final var collFolder = new File(TestGlobals.PATH + File.separator + "driftdb" + File.separator + "driftcoll");
        assertTrue(collFolder.isDirectory(), "the first conform must have created the collection folder");

        deleteRecursively(collFolder);
        assertFalse(collFolder.exists());

        conformer.conform(snapshot);

        assertTrue(collFolder.isDirectory(), "a later sweep must restore the folder under its admin entry");
    }

    @Test
    public void test_conform_creates_missing_index_and_drops_extra_index() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, "stale");
        AdminOperationHelper.saveNewIndex(TestGlobals.DB, TestGlobals.COLL, "stale");

        conformer.conform(snapshot(List.of(dbJson(TestGlobals.DB, List.of())),
                List.of(collJson(TestGlobals.DB, TestGlobals.COLL, Set.of("wanted"))), List.of()));

        final var indexes = cache.getIndexesForCollection(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(indexes.contains("wanted"));
        assertFalse(indexes.contains("stale"));
    }

    @Test
    public void test_conform_installs_schema_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        final var schemas = new JsonObject();
        schemas.add(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL), schema);
        conformer.conform(snapshot(List.of(dbJson(TestGlobals.DB, List.of())),
                List.of(collJson(TestGlobals.DB, TestGlobals.COLL, Set.of())), List.of(), schemas));

        assertEquals(schema, cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_conform_removes_schema_absent_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        final var schema = new JsonObject();
        schema.add("type", new JsonString("object"));
        // Written to disk, not just cached: conform reads the authoritative file.
        IocContainer.get(FileSystem.class).writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL,
                IocContainer.get(EJson.class).toJson(schema));
        cache.putCollectionSchema(TestGlobals.DB, TestGlobals.COLL, schema);

        conformer.conform(snapshot(List.of(dbJson(TestGlobals.DB, List.of())),
                List.of(collJson(TestGlobals.DB, TestGlobals.COLL, Set.of())), List.of()));

        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_conform_reconciles_database_owners() throws Exception {
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("ownersdb", new ArrayList<>(), List.of("old")));

        conformer.conform(snapshot(List.of(dbJson("ownersdb", List.of("new1", "new2"))), List.of(), List.of()));

        final var owners = cache.getAdminDbEntry("ownersdb").getOwners();
        assertTrue(owners.contains("new1"));
        assertTrue(owners.contains("new2"));
        assertFalse(owners.contains("old"));
    }

    @Test
    public void test_conform_drops_local_collection_and_database_absent_from_snapshot() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry("keepdb", new ArrayList<>(), List.of()));

        conformer.conform(snapshot(List.of(dbJson("keepdb", List.of())), List.of(), List.of()));

        assertNull(cache.getAdminDbEntry(TestGlobals.DB));
        assertNotNull(cache.getAdminDbEntry("keepdb"));
    }

    @Test
    public void test_conform_upserts_snapshot_user_and_deletes_absent_user() throws Exception {
        AdminOperationHelper.saveUserEntry(new AdminUserEntry("stale", "h", false, Set.of(), Map.of(), Map.of()));

        conformer.conform(snapshot(List.of(), List.of(), List.of(userJson())));

        assertNotNull(cache.getAdminUserEntry("alice"));
        assertNull(cache.getAdminUserEntry("stale"));
    }

    @Test
    public void test_conform_drops_orphan_collection_in_kept_database() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createTestJoinCollection();

        conformer.conform(snapshot(List.of(dbJson(TestGlobals.DB, List.of())),
                List.of(collJson(TestGlobals.DB, TestGlobals.COLL, Set.of())), List.of()));

        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.JOIN_COLL));
    }
}
