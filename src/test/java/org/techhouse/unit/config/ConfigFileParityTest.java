package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

public class ConfigFileParityTest {

    @Test
    public void test_shipped_config_only_declares_keys_the_packaged_default_knows() throws Exception {
        final var packaged = readKeyValues(Path.of("src", "main", "resources", "default.cfg"));
        final var shipped = readKeyValues(Path.of("lwnrdb.cfg"));

        for (final var key : shipped.keySet()) {
            assertTrue(packaged.containsKey(key), key + " is in lwnrdb.cfg but not in default.cfg");
        }
    }

    @Test
    public void test_shipped_overrides_agree_with_the_packaged_defaults() throws Exception {
        final var packaged = readKeyValues(Path.of("src", "main", "resources", "default.cfg"));
        final var shipped = readKeyValues(Path.of("lwnrdb.cfg"));

        for (final var entry : shipped.entrySet()) {
            assertEquals(packaged.get(entry.getKey()), entry.getValue(),
                    entry.getKey() + " ships a value that differs from the packaged default");
        }
    }

    private static Map<String, String> readKeyValues(Path path) throws Exception {
        final var result = new TreeMap<String, String>();
        for (final var line : Files.readAllLines(path)) {
            final var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final var separator = trimmed.indexOf('=');
            result.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        return result;
    }
}
