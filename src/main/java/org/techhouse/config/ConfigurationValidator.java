package org.techhouse.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static List<String> validate(Map<String, String> configs) {
        final List<String> errors = new ArrayList<>();
        validatePort(configs, errors);
        validateInt(configs, "maxConnections", 0, errors);
        validateInt(configs, "backgroundProcessingThreads", 1, errors);
        validateInt(configs, "maxLogFiles", 1, errors);
        validateWritablePath(configs, "filePath", errors);
        validateWritablePath(configs, "logPath", errors);
        final var maxPageSize = validatePositiveSize(configs, "maxPageSize", errors);
        final var maxEntrySize = validatePositiveSize(configs, "maxEntrySize", errors);
        if (maxPageSize != null && maxEntrySize != null && maxPageSize <= maxEntrySize) {
            errors.add("maxPageSize (" + maxPageSize + ") must be greater than maxEntrySize (" + maxEntrySize + ")");
        }
        validateAdminUsername(configs, errors);
        validateAdminPassword(configs, errors);
        validateMaxMemory(configs, errors);
        return errors;
    }

    private static void validatePort(Map<String, String> configs, List<String> errors) {
        final var value = configs.get("port");
        if (notAnInt(value, port -> port >= 1 && port <= 65535)) {
            errors.add("port must be a valid number between 1 and 65535, but was: " + value);
        }
    }

    private static void validateInt(Map<String, String> configs, String key, int min, List<String> errors) {
        final var value = configs.get(key);
        if (notAnInt(value, parsed -> parsed >= min)) {
            errors.add(key + " must be a valid number greater than or equal to " + min + ", but was: " + value);
        }
    }

    private static boolean notAnInt(String value, IntPredicate predicate) {
        if (value == null) {
            return true;
        }
        try {
            return !predicate.test(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static void validateWritablePath(Map<String, String> configs, String key, List<String> errors) {
        final var value = configs.get(key);
        if (value == null || value.isBlank()) {
            errors.add(key + " must be a non-blank path");
            return;
        }
        final Path path = Paths.get(value.trim());
        try {
            Files.createDirectories(path);
        } catch (IOException | RuntimeException e) {
            errors.add(key + " (" + value + ") could not be created: " + e.getMessage());
            return;
        }
        if (!Files.isWritable(path)) {
            errors.add(key + " (" + value + ") is not writable by the application");
        }
    }

    private static Long validatePositiveSize(Map<String, String> configs, String key, List<String> errors) {
        final var value = configs.get(key);
        try {
            final var parsed = SizeParser.parse(value);
            if (parsed <= 0) {
                errors.add(key + " must be a valid size greater than 0, but was: " + value);
                return null;
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            errors.add(key + " must be a valid size (e.g. 2Mb), but was: " + value);
            return null;
        }
    }

    private static void validateAdminUsername(Map<String, String> configs, List<String> errors) {
        final var value = configs.get("defaultAdminUsername");
        if (value == null || value.isBlank()) {
            errors.add("defaultAdminUsername must be a non-blank string");
        }
    }

    private static void validateAdminPassword(Map<String, String> configs, List<String> errors) {
        final var value = configs.get("defaultAdminPassword");
        if (value == null || value.isBlank()) {
            errors.add("defaultAdminPassword must be a non-blank string");
        } else if (value.length() < Globals.PASSWORD_MIN_LENGTH) {
            errors.add("defaultAdminPassword must be at least " + Globals.PASSWORD_MIN_LENGTH + " characters");
        }
    }

    private static void validateMaxMemory(Map<String, String> configs, List<String> errors) {
        final var value = configs.get("maxMemory");
        try {
            // SizeParser already accepts 0 (unlimited) and -1 (disabled) as valid values.
            SizeParser.parse(value);
        } catch (IllegalArgumentException e) {
            errors.add(
                    "maxMemory must be a valid size (e.g. 512Mb), 0 (unlimited) or -1 (disabled), but was: " + value);
        }
    }
}
