package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.conn.PreAuthSlots;

public class PreAuthSlotsTest {
    @Test
    public void test_an_address_is_capped_and_a_release_frees_a_slot() {
        final var slots = new PreAuthSlots(() -> 2);
        assertTrue(slots.acquire("10.0.0.1"));
        assertTrue(slots.acquire("10.0.0.1"));
        assertFalse(slots.acquire("10.0.0.1"));
        assertTrue(slots.acquire("10.0.0.2"));
        slots.release("10.0.0.1");
        assertTrue(slots.acquire("10.0.0.1"));
        assertEquals(2, slots.heldBy("10.0.0.1"));
    }

    @Test
    public void test_releasing_the_last_slot_forgets_the_address() {
        final var slots = new PreAuthSlots(() -> 1);
        assertTrue(slots.acquire("10.0.0.3"));
        slots.release("10.0.0.3");
        slots.release("10.0.0.3");
        assertEquals(0, slots.heldBy("10.0.0.3"));
        assertTrue(slots.acquire("10.0.0.3"));
    }

    @Test
    public void test_zero_means_unlimited() {
        final var slots = new PreAuthSlots(() -> 0);
        for (var i = 0; i < 100; i++) {
            assertTrue(slots.acquire("10.0.0.4"));
        }
        assertEquals(100, slots.heldBy("10.0.0.4"));
    }

    @Test
    public void test_the_default_limit_comes_from_the_configuration() {
        final var slots = new PreAuthSlots();
        for (var i = 0; i < 16; i++) {
            assertTrue(slots.acquire("10.0.0.5"));
        }
        assertFalse(slots.acquire("10.0.0.5"));
    }
}
