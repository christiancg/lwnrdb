package org.techhouse.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

public final class ConfigurationValidator {

    private ConfigurationValidator() {
    }

    public static List<String> validate(Map<String, String> configs) {
        final List<String> errors = new ArrayList<>();
        for (final var key : ConfigKey.all()) {
            final var problem = key.validate(configs.getOrDefault(key.key(), key.defaultValue()));
            if (problem != null) {
                errors.add(key.key() + " " + problem);
            }
        }
        validateWritablePath(configs, "filePath", errors);
        validateWritablePath(configs, "logPath", errors);
        validatePageAndEntrySize(configs, errors);
        validateAdminUsername(configs, errors);
        validateAdminPassword(configs, errors);
        validateCursorBatchSizes(configs, errors);
        validateTls(configs, errors);
        if (Boolean.parseBoolean(configs.getOrDefault("clusterEnabled", "false").trim())) {
            validateEnabledClusterConstraints(configs, errors);
        }
        validateScriptZoneAndLocale(configs, errors);
        return errors;
    }

    private static void validatePageAndEntrySize(Map<String, String> configs, List<String> errors) {
        final var maxPageSize = sizeOrNull(configs.get("maxPageSize"));
        final var maxEntrySize = sizeOrNull(configs.get("maxEntrySize"));
        if (maxPageSize != null && maxEntrySize != null && maxPageSize <= maxEntrySize) {
            errors.add("maxPageSize (" + maxPageSize + ") must be greater than maxEntrySize (" + maxEntrySize + ")");
        }
    }

    private static Long sizeOrNull(String value) {
        try {
            return value == null ? null : SizeParser.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void validateScriptZoneAndLocale(Map<String, String> configs, List<String> errors) {
        final var zone = configs.get("scriptTimeZone");
        if (isBlank(zone)) {
            errors.add("scriptTimeZone must be a non-blank IANA time zone id (e.g. UTC)");
        } else {
            try {
                var _ = ZoneId.of(zone.trim());
            } catch (DateTimeException e) {
                errors.add("scriptTimeZone must be a valid time zone id, but was: " + zone);
            }
        }
        final var locale = configs.get("scriptLocale");
        if (isBlank(locale) || !Locale.forLanguageTag(locale.trim()).toLanguageTag().equals(locale.trim())) {
            errors.add("scriptLocale must be a valid BCP 47 language tag (e.g. en-US), but was: " + locale);
        }
    }

    private static void validateCursorBatchSizes(Map<String, String> configs, List<String> errors) {
        final var batchSize = configs.get("scriptCursorBatchSize");
        final var maxBatchSize = configs.get("scriptCursorMaxBatchSize");
        if (notAnInt(batchSize, parsed -> parsed >= 1) || notAnInt(maxBatchSize, parsed -> parsed >= 1)) {
            return;
        }
        if (Integer.parseInt(batchSize.trim()) > Integer.parseInt(maxBatchSize.trim())) {
            errors.add("scriptCursorBatchSize (" + batchSize + ") must not be greater than scriptCursorMaxBatchSize ("
                    + maxBatchSize + ")");
        }
    }

    private static void validateEnabledClusterConstraints(Map<String, String> configs, List<String> errors) {
        final var suspect = parseLongOrNull(configs.get("suspectTimeoutMs"));
        final var dead = parseLongOrNull(configs.get("deadTimeoutMs"));
        if (suspect != null && dead != null && dead <= suspect) {
            errors.add("deadTimeoutMs (" + dead + ") must be greater than suspectTimeoutMs (" + suspect + ")");
        }
        final var port = parseIntOrNull(configs.get("port"));
        final var clusterPort = parseIntOrNull(configs.get("clusterPort"));
        if (port != null && clusterPort != null && port.intValue() == clusterPort.intValue()) {
            errors.add("clusterPort (" + clusterPort + ") must be different from port (" + port + ")");
        }
        if (isBlank(configs.get("clusterBindAddress"))) {
            errors.add("clusterBindAddress must be a non-blank address when clusterEnabled is true");
        }
        if (isBlank(configs.get("clusterAdvertisedAddress"))) {
            errors.add("clusterAdvertisedAddress must be a non-blank address when clusterEnabled is true");
        }
        if (isBlank(configs.get("clusterSecret"))) {
            errors.add("clusterSecret must be a non-blank string when clusterEnabled is true");
        }
        validateSeeds(configs.get("clusterSeeds"), errors);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void validateTls(Map<String, String> configs, List<String> errors) {
        final var enabledValue = configs.get("tlsEnabled");
        if (enabledValue == null || isNotBoolean(enabledValue)) {
            errors.add("tlsEnabled must be true or false, but was: " + enabledValue);
            return;
        }
        if (!Boolean.parseBoolean(enabledValue.trim())) {
            // When TLS is disabled the keystore keys are ignored.
            return;
        }
        final var keystorePath = configs.get("tlsKeystorePath");
        if (keystorePath == null || keystorePath.isBlank()) {
            errors.add("tlsKeystorePath must be a non-blank path when tlsEnabled is true");
        } else {
            // The keystore file itself may not exist yet (it is generated on first start),
            // so we only require its parent directory to be creatable and writable.
            final Path parent = Paths.get(keystorePath.trim()).toAbsolutePath().getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                    if (!Files.isWritable(parent)) {
                        errors.add("tlsKeystorePath (" + keystorePath + ") parent directory is not writable");
                    }
                } catch (IOException | RuntimeException e) {
                    errors.add("tlsKeystorePath (" + keystorePath + ") parent directory could not be created: "
                            + e.getMessage());
                }
            }
        }
        final var keystorePassword = configs.get("tlsKeystorePassword");
        if (keystorePassword == null || keystorePassword.isBlank()) {
            errors.add("tlsKeystorePassword must be a non-blank string when tlsEnabled is true");
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

    private static void validateSeeds(String seeds, List<String> errors) {
        if (seeds == null || seeds.isBlank()) {
            return;
        }
        for (var seed : seeds.split(Globals.CLUSTER_SEED_SEPARATOR)) {
            final var trimmed = seed.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final var parts = trimmed.split(Globals.CLUSTER_ADDRESS_SEPARATOR, 2);
            if (parts.length != 2 || parts[0].isBlank() || notAnInt(parts[1], p -> p >= 1 && p <= 65535)) {
                errors.add("clusterSeeds entry must be host:port with a port between 1 and 65535, but was: " + trimmed);
            }
        }
    }

    private static boolean isNotBoolean(String value) {
        final var trimmed = value.trim();
        return !trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false");
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
}
