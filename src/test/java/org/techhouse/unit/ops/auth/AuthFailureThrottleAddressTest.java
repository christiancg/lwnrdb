package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.auth.AuthFailureThrottle;

public class AuthFailureThrottleAddressTest {
    private static final String ADDRESS = "5.6.7.8";
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AuthFailureThrottle throttle = new AuthFailureThrottle(now::get, 250, 30_000,
            AuthFailureThrottle.MAX_KEYS);

    @Test
    public void test_spraying_many_usernames_from_one_address_starts_delaying_after_the_grace() {
        final var addressKey = AuthFailureThrottle.addressKeyOf(ADDRESS);
        for (var i = 0; i < AuthFailureThrottle.ADDRESS_FAILURE_GRACE; i++) {
            throttle.recordAddressFailure(ADDRESS);
            assertEquals(0, throttle.reserve(addressKey), "delayed after " + (i + 1) + " failures");
        }
        throttle.recordAddressFailure(ADDRESS);
        assertEquals(250, throttle.remainingDelayMillis(addressKey));
    }

    @Test
    public void test_parallel_reservations_queue_instead_of_guessing_together() {
        final var key = AuthFailureThrottle.keyOf(ADDRESS, "admin");
        throttle.recordFailure(key);
        throttle.recordFailure(key);
        final var first = throttle.reserve(key);
        final var second = throttle.reserve(key);
        final var third = throttle.reserve(key);
        assertEquals(500, first);
        assertEquals(1_000, second);
        assertEquals(1_500, third);
    }

    @Test
    public void test_a_failure_never_pulls_the_queue_back_below_slots_already_handed_out() {
        final var key = AuthFailureThrottle.keyOf(ADDRESS, "admin");
        throttle.recordFailure(key);
        throttle.recordFailure(key);
        throttle.reserve(key);
        throttle.reserve(key);
        final var queuedUntil = throttle.remainingDelayMillis(key);
        throttle.recordFailure(key);
        assertTrue(throttle.remainingDelayMillis(key) >= queuedUntil,
                throttle.remainingDelayMillis(key) + " < " + queuedUntil);
        assertTrue(throttle.reserve(key) >= queuedUntil);
    }

    @Test
    public void test_a_key_without_failures_reserves_nothing() {
        assertEquals(0, throttle.reserve(AuthFailureThrottle.keyOf(ADDRESS, "nobody")));
    }

    @Test
    public void test_a_reservation_after_the_delay_has_passed_does_not_wait() {
        final var key = AuthFailureThrottle.keyOf(ADDRESS, "admin");
        throttle.recordFailure(key);
        now.addAndGet(10_000);
        assertEquals(0, throttle.reserve(key));
        assertTrue(throttle.remainingDelayMillis(key) > 0);
    }

    @Test
    public void test_the_address_key_never_collides_with_a_username() {
        assertTrue(AuthFailureThrottle.addressKeyOf(ADDRESS).endsWith("|*"));
    }
}
