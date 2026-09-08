package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;

public class ConfigKeyTest {
    private static Map<String, String> shippedDefaults() throws IOException {
        final var defaults = new LinkedHashMap<String, String>();
        final var stream = ConfigKey.class.getResourceAsStream("/default.cfg");
        assertNotNull(stream, "default.cfg must ship on the classpath");
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final var trimmed = line.trim();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                final var split = trimmed.indexOf('=');
                defaults.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
            }
        }
        return defaults;
    }

    @Test
    public void test_every_key_is_declared_in_default_cfg() throws IOException {
        final var defaults = shippedDefaults();
        for (final var key : ConfigKey.all()) {
            assertNotNull(defaults.get(key.key()), key.key() + " is missing from default.cfg");
        }
    }

    @Test
    public void test_default_cfg_declares_no_unknown_key() throws IOException {
        final var known = ConfigKey.all().stream().map(ConfigKey::key).toList();
        for (final var key : shippedDefaults().keySet()) {
            assertTrue(known.contains(key), key + " in default.cfg has no ConfigKey");
        }
    }

    @Test
    public void test_registry_default_matches_default_cfg() throws IOException {
        final var defaults = shippedDefaults();
        for (final var key : ConfigKey.all()) {
            assertEquals(defaults.get(key.key()), key.defaultValue(), key.key() + " default drifted from default.cfg");
        }
    }

    @Test
    public void test_every_shipped_default_passes_its_own_rule() {
        for (final var key : ConfigKey.all()) {
            assertNull(key.validate(key.defaultValue()), key.key() + " ships a default its own rule rejects");
        }
    }
}
