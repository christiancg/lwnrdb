package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.AdminAntiEntropyService;

public class AdminTermOrderingTest {
    private static boolean outranks(long epoch, String nodeId, long bestEpoch, String bestNodeId) throws Exception {
        final Method method = AdminAntiEntropyService.class.getDeclaredMethod("outranks", long.class, String.class,
                long.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, epoch, nodeId, bestEpoch, bestNodeId);
    }

    @Test
    public void test_a_higher_epoch_always_wins() throws Exception {
        assertTrue(outranks(5L, "a", 4L, "z"));
        assertFalse(outranks(4L, "z", 5L, "a"));
    }

    @Test
    public void test_equal_epochs_break_deterministically_on_node_id() throws Exception {
        assertTrue(outranks(5L, "b", 5L, "a"));
        assertFalse(outranks(5L, "a", 5L, "b"));
    }

    @Test
    public void test_both_directions_of_an_equal_epoch_pair_agree_on_the_winner() throws Exception {
        final var aBeatsB = outranks(7L, "node-a", 7L, "node-b");
        final var bBeatsA = outranks(7L, "node-b", 7L, "node-a");
        assertFalse(aBeatsB && bBeatsA, "both nodes cannot win");
        assertTrue(aBeatsB || bBeatsA, "an equal-epoch pair must resolve rather than stay a fixed point");
    }

    @Test
    public void test_an_identical_snapshot_does_not_outrank_itself() throws Exception {
        assertFalse(outranks(5L, "a", 5L, "a"));
    }

    @Test
    public void test_a_missing_node_id_never_wins_a_tie() throws Exception {
        assertFalse(outranks(5L, null, 5L, "a"));
        assertFalse(outranks(5L, "a", 5L, null));
    }
}
