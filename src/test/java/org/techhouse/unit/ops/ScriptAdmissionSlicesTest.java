package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.ops.ScriptAdmission;

public class ScriptAdmissionSlicesTest {

    private static ScriptAdmission admission(int nodeWide, long waitMs, int perUser, int perDatabase) {
        final var admission = new ScriptAdmission(nodeWide, waitMs);
        admission.reconfigure(nodeWide, waitMs, perUser, perDatabase);
        return admission;
    }

    @Test
    public void test_the_shipped_slices_are_not_unlimited() {
        assertEquals("4", ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_USER.defaultValue());
        assertEquals("8", ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_DATABASE.defaultValue());
    }

    @Test
    public void test_one_user_cannot_hold_every_script_slot() {
        final var admission = admission(10, 0L, 2, 0);
        final var first = admission.acquire("alice", "db1");
        final var second = admission.acquire("alice", "db2");
        assertNotNull(first);
        assertNotNull(second);

        assertNull(admission.acquire("alice", "db3"));
        assertEquals(ScriptAdmission.SCOPE_USER, admission.lastRefusalScope());
        assertNotNull(admission.acquire("bob", "db1"), "another user still gets a node-wide permit");
        assertEquals(1, admission.getRejectedPerUser());
    }

    @Test
    public void test_per_database_cap_is_enforced() {
        final var admission = admission(10, 0L, 0, 2);
        assertNotNull(admission.acquire("alice", "shared"));
        assertNotNull(admission.acquire("bob", "shared"));

        assertNull(admission.acquire("carol", "shared"));
        assertEquals(ScriptAdmission.SCOPE_DATABASE, admission.lastRefusalScope());
        assertNotNull(admission.acquire("carol", "other"));
        assertEquals(1, admission.getRejectedPerDatabase());
    }

    @Test
    public void test_a_tenant_at_its_ceiling_is_refused_before_the_node_wide_wait() {
        final var admission = admission(1, 5_000L, 1, 0);
        final var held = admission.acquire("alice", "db");
        assertNotNull(held);

        final var start = System.nanoTime();
        assertNull(admission.acquire("alice", "db"));
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 2_000L, "waited " + elapsedMs + "ms for a node-wide permit it could never use");
        assertEquals(ScriptAdmission.SCOPE_USER, admission.lastRefusalScope());
        assertEquals(0, admission.getRejected(), "the node-wide pool was never asked");
    }

    @Test
    public void test_a_node_wide_refusal_returns_the_slices_it_took() {
        final var admission = admission(1, 0L, 1, 1);
        assertTrue(admission.tryAcquire());

        assertNull(admission.acquire("alice", "db"));
        assertEquals(ScriptAdmission.SCOPE_NODE, admission.lastRefusalScope());

        admission.release();
        assertNotNull(admission.acquire("alice", "db"), "the refused attempt must not have kept alice's slice");
    }

    @Test
    public void test_slices_do_not_take_a_node_wide_permit() {
        final var admission = admission(1, 0L, 1, 0);
        assertTrue(admission.tryAcquire());

        final var slices = admission.acquireSlices("alice", "db");
        assertNotNull(slices, "a trigger or schedule is bounded by its own pool, not the node-wide one");
        assertNull(admission.acquireSlices("alice", "db"));
        slices.close();
        assertNotNull(admission.acquireSlices("alice", "db"));
        assertEquals(0, admission.available());
    }
}
