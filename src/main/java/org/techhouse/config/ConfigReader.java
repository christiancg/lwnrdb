package org.techhouse.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.techhouse.fs.io.TextFiles;
import org.techhouse.log.Logger;

public final class ConfigReader {
    private ConfigReader() {
    }
    private static final String COMMENT_PREFIX = "#";
    private static final String DEFAULT_CONFIG_PATH = "/default.cfg";
    private static final Logger logger = Logger.logFor(ConfigReader.class);

    public static Map<String, String> loadConfiguration() {
        final var defaultConfigs = loadDefaultConfig();
        final var fromFile = loadFromFile();
        if (defaultConfigs != null) {
            if (fromFile != null) {
                final var missingConfigs = ConfigKey.all().stream().map(ConfigKey::key)
                        .filter(x -> !fromFile.containsKey(x)).toList();
                if (!missingConfigs.isEmpty()) {
                    logger.warning("The following configs are missing and will be using defaults: "
                            + String.join(",", missingConfigs));
                }
                defaultConfigs.putAll(fromFile);
            }
        }
        return defaultConfigs;
    }

    private static Map<String, String> loadFromFile() {
        final var file = Path.of(".").toAbsolutePath().normalize().resolve(Globals.FILE_CONFIG_NAME);
        try {
            final var allLines = TextFiles.readLinesIfExists(file);
            if (allLines != null) {
                return processFromLines(allLines);
            }
            logger.warning("Could not load configs, using defaults");
        } catch (IOException exception) {
            logger.error("Error while loading " + Globals.FILE_CONFIG_NAME, exception);
        }
        return null;
    }

    private static Map<String, String> loadDefaultConfig() {
        try (var inputStream = ConfigReader.class.getResourceAsStream(DEFAULT_CONFIG_PATH)) {
            if (inputStream != null) {
                try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    final var allLines = reader.lines().toList();
                    return processFromLines(allLines);
                }
            }
        } catch (IOException exception) {
            logger.error("Error while loading " + DEFAULT_CONFIG_PATH, exception);
        }
        return null;
    }

    private static Map<String, String> processFromLines(List<String> lines) {
        final Map<String, String> config = new HashMap<>();
        for (var line : lines) {
            final var trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            final var parts = trimmed.split("=", 2);
            if (parts.length == 2) {
                final var key = parts[0].trim();
                final var value = parts[1].trim();
                config.put(key, value);
            } else {
                logger.warning("Not a valid property: " + line);
            }
        }
        return config;
    }
}
