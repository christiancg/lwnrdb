package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.HeapShares;
import org.techhouse.test.TestUtils;

public class HeapSharesTest {
    private final Configuration config = Configuration.getInstance();
    private String savedMaxMemory;
    private String savedMaxResultBytes;

    @BeforeEach
    public void setUp() {
        savedMaxMemory = config.stringValue(ConfigKey.MAX_MEMORY);
        savedMaxResultBytes = config.stringValue(ConfigKey.AGGREGATE_MAX_RESULT_BYTES);
    }

    @AfterEach
    public void tearDown() {
        config.override(ConfigKey.MAX_MEMORY, savedMaxMemory);
        config.override(ConfigKey.AGGREGATE_MAX_RESULT_BYTES, savedMaxResultBytes);
    }

    private static long heap() {
        return Runtime.getRuntime().maxMemory();
    }

    @Test
    public void autoMaxMemoryIsFortyPercentOfTheHeap() {
        for (final var spelling : new String[]{"auto", "AUTO", " Auto "}) {
            config.override(ConfigKey.MAX_MEMORY, spelling);
            assertEquals((long) (heap() * Globals.AUTO_MAX_MEMORY_HEAP_FRACTION), config.getMaxMemoryBytes());
            assertFalse(config.isCachingDisabled());
            assertFalse(config.isCacheUnlimited());
        }
    }

    @Test
    public void explicitMaxMemoryAboveSixtyPercentIsClampedAndRemembered() {
        final var ceiling = (long) (heap() * Globals.MAX_MEMORY_HEAP_CEILING);
        final var configured = String.valueOf(ceiling + 1);
        config.override(ConfigKey.MAX_MEMORY, configured);

        assertEquals(ceiling, config.getMaxMemoryBytes());
        assertEquals(ceiling, HeapShares.maxMemoryCeiling());
        assertTrue(HeapShares.clampsMaxMemory(config.stringValue(ConfigKey.MAX_MEMORY)));
        assertEquals(configured, config.stringValue(ConfigKey.MAX_MEMORY));
    }

    @Test
    public void explicitMaxMemoryAtTheCeilingIsKept() {
        final var ceiling = HeapShares.maxMemoryCeiling();
        config.override(ConfigKey.MAX_MEMORY, String.valueOf(ceiling));

        assertEquals(ceiling, config.getMaxMemoryBytes());
        assertFalse(HeapShares.clampsMaxMemory(String.valueOf(ceiling)));
    }

    @Test
    public void zeroAndMinusOneKeepTheirMeaning() {
        config.override(ConfigKey.MAX_MEMORY, "0");
        assertTrue(config.isCacheUnlimited());
        assertFalse(HeapShares.clampsMaxMemory("0"));
        config.override(ConfigKey.MAX_MEMORY, "-1");
        assertTrue(config.isCachingDisabled());
        assertFalse(HeapShares.clampsMaxMemory("-1"));
    }

    @Test
    public void autoAggregateMaxResultBytesIsAQuarterOfTheHeap() {
        config.override(ConfigKey.AGGREGATE_MAX_RESULT_BYTES, "auto");
        assertEquals((long) (heap() * Globals.AUTO_AGGREGATE_RESULT_HEAP_FRACTION),
                config.sizeValue(ConfigKey.AGGREGATE_MAX_RESULT_BYTES));
        config.override(ConfigKey.AGGREGATE_MAX_RESULT_BYTES, "2Mb");
        assertEquals(2L * 1024 * 1024, config.sizeValue(ConfigKey.AGGREGATE_MAX_RESULT_BYTES));
    }

    @Test
    public void autoIsAcceptedOnlyWhereAKeyHasAnAutoValue() {
        assertNull(ConfigKey.MAX_MEMORY.validate("auto"));
        assertNull(ConfigKey.AGGREGATE_MAX_RESULT_BYTES.validate("Auto"));
        assertNull(ConfigKey.MAX_MEMORY.validate("-1"));
        assertTrue(ConfigKey.MAX_MEMORY.validate("nonsense").contains("valid size"));
        assertTrue(ConfigKey.MAX_PAGE_SIZE.validate("auto").contains("valid size"));
    }

    @Test
    public void aKeyWithoutAnAutoValueRefusesAuto() throws Exception {
        final var savedPageSize = config.stringValue(ConfigKey.MAX_PAGE_SIZE);
        TestUtils.setPrivateField(config, "maxPageSize", "auto");
        try {
            final var thrown = assertThrows(IllegalArgumentException.class, config::getMaxPageSize);
            assertTrue(thrown.getMessage().contains("maxPageSize has no auto value"));
        } finally {
            TestUtils.setPrivateField(config, "maxPageSize", savedPageSize);
        }
    }
}
