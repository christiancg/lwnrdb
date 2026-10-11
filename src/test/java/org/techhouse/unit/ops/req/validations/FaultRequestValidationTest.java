package org.techhouse.unit.ops.req.validations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.ArmFaultRequest;
import org.techhouse.ops.req.AwaitFaultRequest;
import org.techhouse.ops.req.ReleaseFaultRequest;
import org.techhouse.ops.req.validations.RequestValidator;

public class FaultRequestValidationTest {

    private static ArmFaultRequest arm(String point, String action) {
        final var request = new ArmFaultRequest();
        request.setPoint(point);
        request.setAction(action);
        return request;
    }

    private static AwaitFaultRequest await(String point, Long timeoutMs) {
        final var request = new AwaitFaultRequest();
        request.setPoint(point);
        request.setTimeoutMs(timeoutMs);
        return request;
    }

    private static ReleaseFaultRequest release(String point) {
        final var request = new ReleaseFaultRequest();
        request.setPoint(point);
        return request;
    }

    @Test
    public void test_every_valid_arm_shape_passes() {
        assertTrue(RequestValidator.validate(arm("WRITE_OWNERSHIP_RECHECKED", "PAUSE")).isValid());
        assertTrue(RequestValidator.validate(arm("delete_tombstone_reserved", "halt")).isValid());
        assertTrue(RequestValidator.validate(arm("PEER_REPLY", "DROP")).isValid());
    }

    @Test
    public void test_an_unknown_or_missing_point_fails() {
        assertFalse(RequestValidator.validate(arm("NOPE", "PAUSE")).isValid());
        assertFalse(RequestValidator.validate(arm(null, "PAUSE")).isValid());
    }

    @Test
    public void test_an_unknown_or_missing_action_fails() {
        assertFalse(RequestValidator.validate(arm("PEER_REPLY", "EXPLODE")).isValid());
        assertFalse(RequestValidator.validate(arm("PEER_REPLY", null)).isValid());
    }

    @Test
    public void test_drop_on_a_point_that_carries_no_message_fails() {
        assertFalse(RequestValidator.validate(arm("WRITE_OWNERSHIP_RECHECKED", "DROP")).isValid());
    }

    @Test
    public void test_the_removed_delay_action_fails() {
        assertFalse(RequestValidator.validate(arm("ANTI_ENTROPY_SNAPSHOTTED", "DELAY")).isValid());
    }

    @Test
    public void test_await_needs_a_point_and_a_timeout_in_range() {
        assertTrue(RequestValidator.validate(await("PEER_REPLY", 1L)).isValid());
        assertTrue(RequestValidator.validate(await("PEER_REPLY", 120_000L)).isValid());
        assertFalse(RequestValidator.validate(await(null, 1000L)).isValid());
        assertFalse(RequestValidator.validate(await("PEER_REPLY", null)).isValid());
        assertFalse(RequestValidator.validate(await("PEER_REPLY", 0L)).isValid());
        assertFalse(RequestValidator.validate(await("PEER_REPLY", 120_001L)).isValid());
    }

    @Test
    public void test_release_takes_no_point_or_a_known_one() {
        assertTrue(RequestValidator.validate(release(null)).isValid());
        assertTrue(RequestValidator.validate(release("PEER_REPLY")).isValid());
        assertFalse(RequestValidator.validate(release("NOPE")).isValid());
    }
}
