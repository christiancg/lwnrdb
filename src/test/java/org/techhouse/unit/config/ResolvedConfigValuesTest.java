package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;

public class ResolvedConfigValuesTest {
    private static final ConfigKey[] KEYS = {ConfigKey.MAX_CONNECTIONS, ConfigKey.AUTH_FAILURE_DELAY_MAX_MS,
            ConfigKey.MAX_PAGE_SIZE, ConfigKey.SYNC_WRITES};

    private final Configuration config = Configuration.getInstance();
    private final String[] saved = new String[KEYS.length];

    @BeforeEach
    public void setUp() {
        for (var i = 0; i < KEYS.length; i++) {
            saved[i] = config.stringValue(KEYS[i]);
        }
    }

    @AfterEach
    public void tearDown() {
        for (var i = 0; i < KEYS.length; i++) {
            config.override(KEYS[i], saved[i]);
        }
    }

    @Test
    public void anOverrideReplacesTheResolvedValueOfEveryKind() {
        config.intValue(ConfigKey.MAX_CONNECTIONS);
        config.longValue(ConfigKey.AUTH_FAILURE_DELAY_MAX_MS);
        config.sizeValue(ConfigKey.MAX_PAGE_SIZE);
        config.booleanValue(ConfigKey.SYNC_WRITES);

        config.override(ConfigKey.MAX_CONNECTIONS, "7");
        config.override(ConfigKey.AUTH_FAILURE_DELAY_MAX_MS, "1234");
        config.override(ConfigKey.MAX_PAGE_SIZE, "4Kb");
        config.override(ConfigKey.SYNC_WRITES, "false");

        assertEquals(7, config.intValue(ConfigKey.MAX_CONNECTIONS));
        assertEquals(1234L, config.longValue(ConfigKey.AUTH_FAILURE_DELAY_MAX_MS));
        assertEquals(4096L, config.sizeValue(ConfigKey.MAX_PAGE_SIZE));
        assertFalse(config.booleanValue(ConfigKey.SYNC_WRITES));

        config.override(ConfigKey.SYNC_WRITES, "true");
        assertTrue(config.booleanValue(ConfigKey.SYNC_WRITES));
    }

    @Test
    public void aResolvedValueIsServedAgainUntilOverridden() {
        config.override(ConfigKey.MAX_CONNECTIONS, " 42 ");
        assertEquals(42, config.intValue(ConfigKey.MAX_CONNECTIONS));
        assertEquals(42, config.intValue(ConfigKey.MAX_CONNECTIONS));
    }
}
