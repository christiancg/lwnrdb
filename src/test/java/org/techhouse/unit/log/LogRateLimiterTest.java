package org.techhouse.unit.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.log.LogRateLimiter;

public class LogRateLimiterTest {
    @Test
    public void test_entries_past_the_per_second_allowance_are_suppressed() {
        final var limiter = new LogRateLimiter(2);
        assertEquals("one", limiter.admit("one"));
        assertEquals("two", limiter.admit("two"));
        assertNull(limiter.admit("three"));
        assertNull(limiter.admit("four"));
    }

    @Test
    public void test_the_next_admitted_entry_reports_how_many_were_suppressed() throws Exception {
        final var limiter = new LogRateLimiter(1);
        assertEquals("first", limiter.admit("first"));
        assertNull(limiter.admit("dropped"));
        assertNull(limiter.admit("dropped"));
        Thread.sleep(1_100);
        final var next = limiter.admit("next");
        assertNotNull(next);
        assertTrue(next.startsWith("next"), next);
        assertTrue(next.endsWith("(2 similar entries suppressed)"), next);
        Thread.sleep(1_100);
        assertEquals("clean", limiter.admit("clean"));
    }
}
