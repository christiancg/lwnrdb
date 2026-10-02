package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ReplicatedUserApplyHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ReplicatedUserApplyHelperTest {
    private final Cache cache = IocContainer.get(Cache.class);

    private static AdminUserEntry user(String username) {
        return new AdminUserEntry(username, "hash-" + username, false, Set.of(), new HashMap<>(), new HashMap<>());
    }

    private static ReplicationPayload upsert(AdminUserEntry entry) {
        return new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, ReplicationOp.UPSERT,
                List.of(entry.getData()), null);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_apply_upsert_stores_identical_record() {
        assertTrue(ReplicatedUserApplyHelper.apply(upsert(user("bob"))));
        final var stored = cache.getAdminUserEntry("bob");
        assertNotNull(stored);
        assertEquals("hash-bob", stored.getPasswordHash());
    }

    @Test
    public void test_apply_delete_removes_record() throws Exception {
        AdminOperationHelper.saveUserEntry(user("carol"));
        assertTrue(ReplicatedUserApplyHelper.apply(new ReplicationPayload(Globals.ADMIN_DB_NAME,
                Globals.ADMIN_USERS_COLLECTION_NAME, ReplicationOp.DELETE, null, List.of("carol"))));
        assertNull(cache.getAdminUserEntry("carol"));
    }

    private static ReplicationPayload delete(String username) {
        return new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, ReplicationOp.DELETE,
                null, List.of(username));
    }

    private void ownTestDatabase(String... owners) throws Exception {
        AdminOperationHelper.saveDatabaseEntry(
                new AdminDbEntry(TestGlobals.DB, new ArrayList<>(), new ArrayList<>(List.of(owners))));
    }

    @Test
    public void test_apply_delete_removes_the_user_from_the_database_owners() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(user("dave"));
        ownTestDatabase("dave", "erin");

        assertTrue(ReplicatedUserApplyHelper.apply(delete("dave")));

        assertEquals(List.of("erin"), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }

    @Test
    public void test_apply_delete_of_an_unknown_user_still_prunes_the_owners() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        ownTestDatabase("ghost");

        assertTrue(ReplicatedUserApplyHelper.apply(delete("ghost")));

        assertEquals(List.of(), cache.getAdminDbEntry(TestGlobals.DB).getOwners());
    }

    @Test
    public void test_a_user_recreated_under_a_deleted_name_does_not_inherit_ownership() throws Exception {
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveUserEntry(user("frank"));
        ownTestDatabase("frank");

        ReplicatedUserApplyHelper.apply(delete("frank"));
        AdminOperationHelper.saveUserEntry(user("frank"));

        assertFalse(cache.getAdminDbEntry(TestGlobals.DB).isOwner("frank"));
    }

    @Test
    public void test_apply_null_or_opless_payload_returns_false() {
        assertFalse(ReplicatedUserApplyHelper.apply(null));
        assertFalse(ReplicatedUserApplyHelper.apply(new ReplicationPayload()));
    }
}
