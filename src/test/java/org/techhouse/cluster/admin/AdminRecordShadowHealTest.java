package org.techhouse.cluster.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.AdminStamp;
import org.techhouse.ops.admin.StoredDefinitions;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminRecordShadowHealTest extends AdminRecordTestBase {
    private static final String NAME = "zombie";
    private final ScheduleRegistry registry = IocContainer.get(ScheduleRegistry.class);

    private static void shadow(AdminRecord live, long tombstoneVersion) throws Exception {
        AdminRecordMerge.apply(List.of(live), true);
        AdminStamp.tombstoneAt(live.key(), tombstoneVersion);
    }

    private void shadowSchedule() throws Exception {
        shadow(AdminRecordStaleInstallTest.schedule(NAME, clock.next()), clock.next());
        registry.reload(TestGlobals.DB);
        assertNotNull(registry.get(TestGlobals.DB, NAME));
    }

    @Test
    public void test_a_shadowed_schedule_is_removed_and_unregistered() throws Exception {
        shadowSchedule();

        assertTrue(AdminRecordMerge.removeShadowed());

        assertNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
        assertNull(registry.get(TestGlobals.DB, NAME));
    }

    @Test
    public void test_a_shadowed_collection_is_quarantined() throws Exception {
        shadow(collection(TestGlobals.DB, "zombie_coll", clock.next()), clock.next());

        assertTrue(AdminRecordMerge.removeShadowed());

        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "zombie_coll"));
    }

    @Test
    public void test_a_shadowed_user_is_deleted() throws Exception {
        shadow(user("zed", clock.next()), clock.next());

        assertTrue(AdminRecordMerge.removeShadowed());

        assertNull(cache.getAdminUserEntry("zed"));
    }

    @Test
    public void test_an_equal_version_tombstone_shadows_the_record() throws Exception {
        final var version = clock.next();
        shadow(AdminRecordStaleInstallTest.schedule(NAME, version), version);

        assertTrue(AdminRecordMerge.removeShadowed());

        assertNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
    }

    @Test
    public void test_a_record_newer_than_its_tombstone_is_not_touched() throws Exception {
        final var older = clock.next();
        shadow(AdminRecordStaleInstallTest.schedule(NAME, clock.next()), older);

        assertFalse(AdminRecordMerge.removeShadowed());

        assertNotNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
    }

    @Test
    public void test_a_shadowed_record_whose_lock_stays_held_is_retried() throws Exception {
        shadowSchedule();
        final var locks = IocContainer.get(ResourceLocking.class);
        Thread.ofVirtual().start(() -> {
            try {
                locks.lock(TestGlobals.DB, Globals.SCHEDULES_FOLDER);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).join();
        final var origTimeout = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 50L);
        try {
            assertFalse(AdminRecordMerge.removeShadowed());
            assertNotNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
        } finally {
            TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origTimeout);
            TestUtils.releaseAllLocks();
        }

        assertTrue(AdminRecordMerge.removeShadowed());
        assertNull(StoredDefinitions.schedule(TestGlobals.DB, NAME));
    }
}
