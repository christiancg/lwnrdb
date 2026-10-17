package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.unit.ops.AdmissionAssertions.assertAdmitted;

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
        assertAdmitted(admission.acquire("alice", "db1", false));
        assertAdmitted(admission.acquire("alice", "db2", false));

        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_USER), admission.acquire("alice", "db3", false));
        assertAdmitted(admission.acquire("bob", "db1", false), "another user still gets a node-wide permit");
        assertEquals(1, admission.getRejectedPerUser());
    }

    @Test
    public void test_per_database_cap_is_enforced() {
        final var admission = admission(10, 0L, 0, 3);
        assertAdmitted(admission.acquire("alice", "shared", false));
        assertAdmitted(admission.acquire("bob", "shared", false));

        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_DATABASE),
                admission.acquire("carol", "shared", false));
        assertAdmitted(admission.acquire("carol", "other", false));
        assertEquals(1, admission.getRejectedPerDatabase());
    }

    @Test
    public void test_a_tenant_at_its_ceiling_is_refused_before_the_node_wide_wait() {
        final var admission = admission(1, 5_000L, 1, 0);
        assertAdmitted(admission.acquire("alice", "db", false));

        final var start = System.nanoTime();
        final var refused = admission.acquire("alice", "db", false);
        final var elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMs < 2_000L, "waited " + elapsedMs + "ms for a node-wide permit it could never use");
        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_USER), refused);
        assertEquals(0, admission.getRejected(), "the node-wide pool was never asked");
    }

    @Test
    public void test_a_node_wide_refusal_returns_the_slices_it_took() {
        final var admission = admission(1, 0L, 1, 1);
        assertTrue(admission.tryAcquire());

        assertEquals(new ScriptAdmission.Refusal(ScriptAdmission.SCOPE_NODE), admission.acquire("alice", "db", false));

        admission.release();
        assertAdmitted(admission.acquire("alice", "db", false), "the refused attempt must not have kept alice's slice");
    }

    @Test
    public void test_slices_do_not_take_a_node_wide_permit() {
        final var admission = admission(1, 0L, 1, 0);
        assertTrue(admission.tryAcquire());

        final var slices = assertInstanceOf(ScriptAdmission.Permit.class, admission.acquireSlices("alice", "db"),
                "a trigger or schedule is bounded by its own pool, not the node-wide one");
        assertInstanceOf(ScriptAdmission.Refusal.class, admission.acquireSlices("alice", "db"));
        slices.close();
        assertAdmitted(admission.acquireSlices("alice", "db"));
        assertEquals(0, admission.available());
    }
}
