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
import java.util.function.LongPredicate;

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
        validatePositiveLong(configs, "transactionLockTimeoutMs", errors);
        validatePositiveLong(configs, "shutdownTimeoutMs", errors);
        validateTls(configs, errors);
        validateCluster(configs, errors);
        validateScript(configs, errors);
        return errors;
    }

    private static void validateScript(Map<String, String> configs, List<String> errors) {
        final var zone = configs.get("scriptTimeZone");
        if (isBlank(zone)) {
            errors.add("scriptTimeZone must be a non-blank IANA time zone id (e.g. UTC)");
        } else {
            try {
                // Parsing is the validation: ZoneId.of rejects an unknown id by throwing.
                var _ = ZoneId.of(zone.trim());
            } catch (DateTimeException e) {
                errors.add("scriptTimeZone must be a valid time zone id, but was: " + zone);
            }
        }
        final var locale = configs.get("scriptLocale");
        // Locale.forLanguageTag never throws — it answers the undetermined locale for a malformed tag,
        // so the round-trip is what actually rejects one.
        if (isBlank(locale) || !Locale.forLanguageTag(locale.trim()).toLanguageTag().equals(locale.trim())) {
            errors.add("scriptLocale must be a valid BCP 47 language tag (e.g. en-US), but was: " + locale);
        }
        validateBoolean(configs, "scriptsEnabled", errors);
        validateBoolean(configs, "scriptTextImportEnabled", errors);
        validatePositiveLong(configs, "scriptInstructionBudget", errors);
        validatePositiveLong(configs, "scriptTimeoutMs", errors);
        validateInt(configs, "scriptMaxDepth", 1, errors);
        validatePositiveSize(configs, "scriptMaxSourceBytes", errors);
        validatePositiveSize(configs, "scriptMaxMemoryBytes", errors);
        validateInt(configs, "scriptMaxLogLines", 1, errors);
        validateInt(configs, "scriptMaxLogLineChars", 1, errors);
        validateInt(configs, "procedureCacheSize", 0, errors);
        validatePositiveSize(configs, "procedureCacheMaxBytes", errors);
        validatePositiveSize(configs, "schemaCacheMaxBytes", errors);
        validateInt(configs, "triggerCacheMaxEntries", 0, errors);
        validateInt(configs, "metadataMissCacheMaxEntries", 0, errors);
        validateBoolean(configs, "triggersEnabled", errors);
        validateInt(configs, "triggerThreads", 1, errors);
        validateInt(configs, "triggerQueueSize", 1, errors);
        validateInt(configs, "triggerMaxDepth", 0, errors);
        validatePositiveLong(configs, "triggerTimeoutMs", errors);
        validateBoolean(configs, "triggerRunLogEnabled", errors);
        validatePositiveLong(configs, "triggerRunRetentionMs", errors);
    }

    private static void validateCluster(Map<String, String> configs, List<String> errors) {
        validateBoolean(configs, "clusterEnabled", errors);
        validateBoolean(configs, "clusterTlsEnabled", errors);
        validateBoolean(configs, "readFallbackToLocal", errors);
        if (notAnInt(configs.get("clusterPort"), port -> port >= 1 && port <= 65535)) {
            errors.add(
                    "clusterPort must be a valid number between 1 and 65535, but was: " + configs.get("clusterPort"));
        }
        validateInt(configs, "clusterExpectedSize", 1, errors);
        validateInt(configs, "virtualNodesPerNode", 1, errors);
        validatePositiveLong(configs, "gossipIntervalMs", errors);
        validatePositiveLong(configs, "suspectTimeoutMs", errors);
        validatePositiveLong(configs, "deadTimeoutMs", errors);
        validatePositiveLong(configs, "replicationAckTimeoutMs", errors);
        validatePositiveLong(configs, "antiEntropyIntervalMs", errors);
        validatePositiveLong(configs, "tombstoneRetentionMs", errors);
        final var enabledValue = configs.get("clusterEnabled");
        if (enabledValue == null || isNotBoolean(enabledValue) || !Boolean.parseBoolean(enabledValue.trim())) {
            return;
        }
        validateEnabledClusterConstraints(configs, errors);
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

    private static void validateBoolean(Map<String, String> configs, String key, List<String> errors) {
        final var value = configs.get(key);
        if (value == null || isNotBoolean(value)) {
            errors.add(key + " must be true or false, but was: " + value);
        }
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

    private static boolean isNotBoolean(String value) {
        final var trimmed = value.trim();
        return !trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false");
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

    // Every long-valued key is a duration or a count with the same lower bound, so the bound is not a
    // parameter; a key needing a different one would want its own message anyway.
    private static void validatePositiveLong(Map<String, String> configs, String key, List<String> errors) {
        final var value = configs.get(key);
        if (notALong(value, parsed -> parsed >= 1)) {
            errors.add(key + " must be a valid number greater than or equal to 1, but was: " + value);
        }
    }

    private static boolean notALong(String value, LongPredicate predicate) {
        if (value == null) {
            return true;
        }
        try {
            return !predicate.test(Long.parseLong(value.trim()));
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
