package org.techhouse.cluster.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.HybridClock;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.PermissionLevel;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.admin.AdminTombstone;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

abstract class AdminRecordTestBase {
    protected final Cache cache = IocContainer.get(Cache.class);
    protected final FileSystem fs = IocContainer.get(FileSystem.class);
    protected final HybridClock clock = IocContainer.get(HybridClock.class);
    protected final Configuration config = Configuration.getInstance();
    private boolean origEnabled;

    @BeforeEach
    public void setUpAdminRecords() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
    }

    @AfterEach
    public void tearDownAdminRecords() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    protected static JsonObject bodyOf(DbEntry entry) {
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return body;
    }

    protected AdminRecord database(String dbName, long version, String... owners) {
        return new AdminRecord(AdminRecordKey.database(dbName), version,
                bodyOf(new AdminDbEntry(dbName, new ArrayList<>(), new ArrayList<>(List.of(owners)))));
    }

    protected AdminRecord collection(String dbName, String collName, long version, String... indexes) {
        return collection(dbName, collName, version, 0L, indexes);
    }

    protected AdminRecord collection(String dbName, String collName, long version, long incarnation,
            String... indexes) {
        final var entry = new AdminCollEntry(dbName, collName, Set.of(indexes));
        entry.setIncarnation(incarnation);
        return new AdminRecord(AdminRecordKey.collection(dbName, collName), version, bodyOf(entry));
    }

    protected AdminRecord user(String username, long version) {
        return user(username, version, new HashMap<>());
    }

    protected AdminRecord user(String username, long version, Map<String, PermissionLevel> databaseGrants) {
        return new AdminRecord(AdminRecordKey.user(username), version, bodyOf(
                new AdminUserEntry(username, "hash-" + username, false, Set.of(), databaseGrants, new HashMap<>())));
    }

    protected AdminRecord procedure(String dbName, String name, long version) {
        final var body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("source", "return 1;");
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.procedure(dbName, name), version, body);
    }

    protected AdminRecord schema(String collName, long version) {
        final var schema = new JsonObject();
        schema.addProperty("type", "object");
        final var body = new JsonObject();
        body.add("schema", schema);
        body.addProperty("writeVersion", Long.toString(version));
        return new AdminRecord(AdminRecordKey.schema(TestGlobals.DB, collName), version, body);
    }

    protected static AdminRecord tombstone(AdminRecordKey key, long version) {
        return AdminRecord.tombstone(key, version);
    }

    protected static Map<String, Long> tombstones() throws Exception {
        return AdminTombstone.all();
    }

    protected static long liveVersion(AdminRecordKey key) {
        return Objects.requireNonNull(AdminRecords.live(key)).version();
    }
}
