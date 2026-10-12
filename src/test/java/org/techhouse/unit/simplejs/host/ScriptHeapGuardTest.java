package org.techhouse.unit.simplejs.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;
import org.techhouse.simplejs.host.ScriptHeapGuard;

public class ScriptHeapGuardTest {
    @BeforeEach
    public void enableAtTheShippedCeiling() {
        ScriptHeapGuard.setCeilingPercent(Configuration.getInstance().intValue(ConfigKey.SCRIPT_HEAP_CEILING_PERCENT));
    }

    @AfterEach
    public void switchOff() {
        ScriptHeapGuard.setCeilingPercent(ScriptHeapGuard.OFF);
    }

    @Test
    public void test_an_ordinary_allocation_passes() {
        assertDoesNotThrow(() -> ScriptHeapGuard.check(0L));
        assertDoesNotThrow(() -> ScriptHeapGuard.check(1024L));
    }

    @Test
    public void test_an_allocation_past_the_whole_heap_is_refused() {
        final var refused = assertThrows(ScriptMemoryException.class,
                () -> ScriptHeapGuard.check(Runtime.getRuntime().maxMemory()));
        assertEquals(ScriptHeapGuard.CEILING_MESSAGE, refused.getMessage());
    }

    @Test
    public void test_the_ceiling_follows_the_configured_percentage() {
        ScriptHeapGuard.setCeilingPercent(95);
        final var halfOfTheHeap = Runtime.getRuntime().maxMemory() / 2;
        assertDoesNotThrow(() -> ScriptHeapGuard.check(halfOfTheHeap / 8));
        ScriptHeapGuard.setCeilingPercent(10);
        assertThrows(ScriptMemoryException.class, () -> ScriptHeapGuard.check(halfOfTheHeap));
    }

    @Test
    public void test_a_guard_that_was_never_switched_on_never_refuses() {
        ScriptHeapGuard.setCeilingPercent(ScriptHeapGuard.OFF);
        assertDoesNotThrow(() -> ScriptHeapGuard.check(Runtime.getRuntime().maxMemory()));
    }
}
