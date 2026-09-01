package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * lwnrdb.cfg is the shipped file and default.cfg the packaged fallback, so a key added to one and not the
 * other silently changes what an untouched deployment runs on.
 */
public class ConfigFileParityTest {

    @Test
    public void test_default_and_shipped_config_files_declare_the_same_keys_and_values() throws Exception {
        final var packaged = readKeyValues(Path.of("src", "main", "resources", "default.cfg"));
        final var shipped = readKeyValues(Path.of("lwnrdb.cfg"));

        assertEquals(packaged, shipped);
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
