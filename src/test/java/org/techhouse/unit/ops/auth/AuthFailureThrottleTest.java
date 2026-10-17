package org.techhouse.unit.ops.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.techhouse.ops.auth.AuthFailureThrottle;

public class AuthFailureThrottleTest {
    private static final String KEY = "1.2.3.4|admin";
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private AuthFailureThrottle throttle(long base, long cap, int maxKeys) {
        return new AuthFailureThrottle(now::get, base, cap, maxKeys);
    }

    private AuthFailureThrottle throttle() {
        return throttle(250, 30_000, AuthFailureThrottle.MAX_KEYS);
    }

    @Test
    public void first_attempt_has_no_delay() {
        assertEquals(0, throttle().remainingDelayMillis(KEY));
    }

    @Test
    public void delay_doubles_per_failure_up_to_the_cap() {
        final var throttle = throttle();
        final var delays = new ArrayList<Long>();
        for (var i = 0; i < 10; i++) {
            throttle.recordFailure(KEY);
            delays.add(throttle.remainingDelayMillis(KEY));
        }
        assertEquals(java.util.List.of(250L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L),
                delays);
    }

    @Test
    public void delay_counts_down_with_the_clock() {
        final var throttle = throttle();
        throttle.recordFailure(KEY);
        now.addAndGet(100);
        assertEquals(150, throttle.remainingDelayMillis(KEY));
        now.addAndGet(1_000);
        assertEquals(0, throttle.remainingDelayMillis(KEY));
    }

    @Test
    public void success_clears_the_key() {
        final var throttle = throttle();
        throttle.recordFailure(KEY);
        throttle.recordFailure(KEY);
        throttle.recordSuccess(KEY);
        assertEquals(0, throttle.remainingDelayMillis(KEY));
        throttle.recordFailure(KEY);
        assertEquals(250, throttle.remainingDelayMillis(KEY));
    }

    @Test
    public void delay_is_per_key() {
        final var throttle = throttle();
        throttle.recordFailure(KEY);
        assertEquals(0, throttle.remainingDelayMillis("5.6.7.8|admin"));
        assertEquals(0, throttle.remainingDelayMillis("1.2.3.4|other"));
    }

    @Test
    public void idle_key_expires_after_ten_minutes() {
        final var throttle = throttle(250, 30_000, 10);
        for (var i = 0; i < 20; i++) {
            throttle.recordFailure(KEY);
        }
        now.addAndGet(AuthFailureThrottle.IDLE_EXPIRY_MILLIS + 1);
        assertEquals(0, throttle.remainingDelayMillis(KEY));
        assertEquals(0, throttle.size());
        throttle.recordFailure(KEY);
        assertEquals(250, throttle.remainingDelayMillis(KEY));
    }

    @Test
    public void map_never_exceeds_max_keys_under_username_spray() {
        final var maxKeys = 50;
        final var throttle = throttle(250, 30_000, maxKeys);
        for (var i = 0; i < maxKeys * 3; i++) {
            throttle.recordFailure(AuthFailureThrottle.keyOf("9.9.9.9", "user" + i));
            assertTrue(throttle.size() <= maxKeys);
        }
        assertEquals(maxKeys, throttle.size());
    }

    @Test
    public void long_username_is_truncated_in_the_key() {
        final var longName = "u".repeat(AuthFailureThrottle.MAX_USERNAME_CHARS + 500);
        final var key = AuthFailureThrottle.keyOf("1.2.3.4", longName);
        assertEquals("1.2.3.4|" + "u".repeat(AuthFailureThrottle.MAX_USERNAME_CHARS), key);
        assertEquals(key, AuthFailureThrottle.keyOf("1.2.3.4", longName + "suffix"));
    }

    @Test
    public void key_with_separator_or_empty_username_stays_unambiguous() {
        assertEquals("1.2.3.4|", AuthFailureThrottle.keyOf("1.2.3.4", null));
        assertEquals("1.2.3.4|", AuthFailureThrottle.keyOf("1.2.3.4", ""));
        assertEquals("1.2.3.4|a|b", AuthFailureThrottle.keyOf("1.2.3.4", "a|b"));
    }

    @Test
    public void zero_base_disables_the_delay() {
        final var throttle = throttle(0, 30_000, 10);
        for (var i = 0; i < 40; i++) {
            throttle.recordFailure(KEY);
        }
        assertEquals(0, throttle.remainingDelayMillis(KEY));
    }

    @Test
    public void a_huge_base_is_capped_without_overflow() {
        final var throttle = throttle(Long.MAX_VALUE / 2, 30_000, 10);
        for (var i = 0; i < 40; i++) {
            throttle.recordFailure(KEY);
            assertEquals(30_000, throttle.remainingDelayMillis(KEY));
        }
    }

    @Test
    public void concurrent_failures_lose_no_increment() throws Exception {
        final var throttle = throttle(1, Long.MAX_VALUE / 4, 10);
        final var threads = new ArrayList<Thread>();
        for (var i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                for (var j = 0; j < 2; j++) {
                    throttle.recordFailure(KEY);
                }
            }));
        }
        for (final var thread : threads) {
            thread.join();
        }
        assertEquals(1L << 15, throttle.remainingDelayMillis(KEY));
    }
}
