package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.ConfigurationValidator;

public class ConfigurationValidatorTest {

    private static Map<String, String> baseValid(Path writablePath) {
        final var map = new HashMap<String, String>();
        map.put("port", "8989");
        map.put("maxConnections", "100");
        map.put("filePath", writablePath.toString());
        map.put("backgroundProcessingThreads", "10");
        map.put("logPath", writablePath.toString());
        map.put("maxLogFiles", "7");
        map.put("maxPageSize", "2Mb");
        map.put("maxEntrySize", "1Mb");
        map.put("defaultAdminUsername", "admin");
        map.put("defaultAdminPassword", "administrator");
        map.put("maxMemory", "512Mb");
        map.put("transactionLockTimeoutMs", "5000");
        map.put("tlsEnabled", "false");
        map.put("clusterEnabled", "false");
        map.put("clusterPort", "9990");
        map.put("clusterBindAddress", "0.0.0.0");
        map.put("clusterAdvertisedAddress", "127.0.0.1");
        map.put("clusterSeeds", "");
        map.put("nodeId", "");
        map.put("clusterExpectedSize", "1");
        map.put("gossipIntervalMs", "1000");
        map.put("suspectTimeoutMs", "5000");
        map.put("deadTimeoutMs", "15000");
        map.put("replicationAckTimeoutMs", "5000");
        map.put("virtualNodesPerNode", "128");
        map.put("readFallbackToLocal", "true");
        map.put("clusterTlsEnabled", "false");
        map.put("clusterSecret", "");
        map.put("antiEntropyIntervalMs", "60000");
        map.put("tombstoneRetentionMs", "86400000");
        map.put("scriptTimeZone", "UTC");
        map.put("scriptLocale", "en-US");
        map.put("scriptsEnabled", "false");
        map.put("scriptInstructionBudget", "10000000");
        map.put("scriptTimeoutMs", "5000");
        map.put("scriptMaxDepth", "200");
        map.put("scriptMaxSourceBytes", "256Kb");
        map.put("scriptMaxLogLines", "1000");
        map.put("scriptMaxLogLineChars", "4096");
        map.put("scriptMaxMemoryBytes", "64Mb");
        map.put("scriptMaxResultBytes", "16Mb");
        map.put("scriptCursorBatchSize", "500");
        map.put("scriptCursorMaxBatchSize", "5000");
        map.put("procedureCacheSize", "128");
        map.put("triggersEnabled", "false");
        map.put("triggerThreads", "2");
        map.put("triggerQueueSize", "10000");
        map.put("triggerMaxDepth", "3");
        map.put("triggerTimeoutMs", "1000");
        map.put("shutdownTimeoutMs", "15000");
        map.put("procedureCacheMaxBytes", "32Mb");
        map.put("schemaCacheMaxBytes", "32Mb");
        map.put("triggerCacheMaxEntries", "4096");
        map.put("metadataMissCacheMaxEntries", "4096");
        map.put("triggerRunLogEnabled", "true");
        map.put("triggerRunRetentionMs", "86400000");
        map.put("scriptTextImportEnabled", "false");
        return map;
    }

    @Test
    public void test_valid_configuration_has_no_errors(@TempDir Path tempDir) {
        assertTrue(ConfigurationValidator.validate(baseValid(tempDir)).isEmpty());
    }

    @Test
    public void test_maxConnections_zero_is_valid(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("maxConnections", "0");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_maxMemory_zero_and_minus_one_valid(@TempDir Path tempDir) {
        final var zero = baseValid(tempDir);
        zero.put("maxMemory", "0");
        assertTrue(ConfigurationValidator.validate(zero).isEmpty());
        final var disabled = baseValid(tempDir);
        disabled.put("maxMemory", "-1");
        assertTrue(ConfigurationValidator.validate(disabled).isEmpty());
    }

    @Test
    public void test_invalid_port_values(@TempDir Path tempDir) {
        assertHasError(tempDir, "port", "not-a-number", "port");
        assertHasError(tempDir, "port", "0", "port");
        assertHasError(tempDir, "port", "70000", "port");
        assertMissingKeyFails(tempDir);
    }

    @Test
    public void test_invalid_maxConnections(@TempDir Path tempDir) {
        assertHasError(tempDir, "maxConnections", "-1", "maxConnections");
        assertHasError(tempDir, "maxConnections", "abc", "maxConnections");
    }

    @Test
    public void test_invalid_thread_and_log_counts(@TempDir Path tempDir) {
        assertHasError(tempDir, "backgroundProcessingThreads", "0", "backgroundProcessingThreads");
        assertHasError(tempDir, "maxLogFiles", "0", "maxLogFiles");
    }

    @Test
    public void test_invalid_sizes(@TempDir Path tempDir) {
        assertHasError(tempDir, "maxPageSize", "nonsense", "maxPageSize");
        assertHasError(tempDir, "maxEntrySize", "0", "maxEntrySize");
    }

    @Test
    public void test_page_size_must_be_greater_than_entry_size(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("maxPageSize", "1Mb");
        config.put("maxEntrySize", "1Mb");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be greater than maxEntrySize")));
    }

    @Test
    public void test_invalid_admin_credentials(@TempDir Path tempDir) {
        assertHasError(tempDir, "defaultAdminUsername", "  ", "defaultAdminUsername");
        assertHasError(tempDir, "defaultAdminPassword", "short", "defaultAdminPassword");
        assertHasError(tempDir, "defaultAdminPassword", "  ", "defaultAdminPassword");
    }

    @Test
    public void test_invalid_maxMemory(@TempDir Path tempDir) {
        assertHasError(tempDir, "maxMemory", "nonsense", "maxMemory");
    }

    @Test
    public void test_invalid_transaction_lock_timeout(@TempDir Path tempDir) {
        assertHasError(tempDir, "transactionLockTimeoutMs", "0", "transactionLockTimeoutMs");
        assertHasError(tempDir, "transactionLockTimeoutMs", "not-a-number", "transactionLockTimeoutMs");
    }

    @Test
    public void test_blank_path_fails(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("filePath", "  ");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("filePath")));
    }

    @Test
    public void test_path_pointing_inside_a_file_fails(@TempDir Path tempDir) throws IOException {
        final var regularFile = Files.createFile(tempDir.resolve("a-file"));
        final var config = baseValid(tempDir);
        config.put("filePath", regularFile.resolve("sub_dir").toString());
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("filePath")));
    }

    @Test
    public void test_non_writable_path_fails(@TempDir Path tempDir) {
        final var readOnly = tempDir.resolve("readonly").toFile();
        assertTrue(readOnly.mkdirs());
        try {
            assertTrue(readOnly.setWritable(false));
            // setWritable can be a no-op when running as root; only assert when it took effect.
            if (!readOnly.canWrite()) {
                final var config = baseValid(tempDir);
                config.put("filePath", readOnly.toString());
                final var errors = ConfigurationValidator.validate(config);
                assertTrue(errors.stream().anyMatch(e -> e.contains("not writable")));
            }
        } finally {
            assertTrue(readOnly.setWritable(true));
        }
    }

    @Test
    public void test_multiple_errors_are_aggregated(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("port", "bad");
        config.put("maxConnections", "-1");
        config.put("maxLogFiles", "0");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.size() >= 3);
    }

    @Test
    public void test_tls_disabled_ignores_keystore_keys(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "false");
        config.remove("tlsKeystorePath");
        config.remove("tlsKeystorePassword");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_tls_enabled_valid_keystore(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "true");
        config.put("tlsKeystorePath", tempDir.resolve("certs").resolve("lwnrdb.p12").toString());
        config.put("tlsKeystorePassword", "change_it");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_tls_enabled_requires_non_blank_path(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "true");
        config.put("tlsKeystorePath", "  ");
        config.put("tlsKeystorePassword", "change_it");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("tlsKeystorePath")));
    }

    @Test
    public void test_tls_enabled_requires_non_blank_password(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "true");
        config.put("tlsKeystorePath", tempDir.resolve("lwnrdb.p12").toString());
        config.put("tlsKeystorePassword", "  ");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("tlsKeystorePassword")));
    }

    @Test
    public void test_tls_enabled_uncreatable_parent_dir_fails(@TempDir Path tempDir) throws IOException {
        final var regularFile = Files.createFile(tempDir.resolve("not-a-dir"));
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "true");
        config.put("tlsKeystorePath", regularFile.resolve("sub").resolve("lwnrdb.p12").toString());
        config.put("tlsKeystorePassword", "change_it");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("tlsKeystorePath")));
    }

    @Test
    public void test_tls_invalid_boolean_fails(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("tlsEnabled", "maybe");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("tlsEnabled")));
    }

    @Test
    public void test_tls_missing_enabled_key_fails(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.remove("tlsEnabled");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("tlsEnabled")));
    }

    private void assertHasError(Path tempDir, String key, String value, String expectedFragment) {
        final var config = baseValid(tempDir);
        config.put(key, value);
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains(expectedFragment)), "Expected an error mentioning '"
                + expectedFragment + "' for " + key + "=" + value + ", got: " + errors);
    }

    private void assertMissingKeyFails(Path tempDir) {
        final var config = baseValid(tempDir);
        config.remove("port");
        final var errors = ConfigurationValidator.validate(config);
        assertFalse(errors.isEmpty());
    }

    @Test
    public void test_script_time_zone_accepts_a_fixed_offset_zone(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("scriptTimeZone", "+05:30");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_script_time_zone_rejects_an_unknown_zone(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptTimeZone", "Mars/Olympus", "scriptTimeZone");
    }

    @Test
    public void test_script_time_zone_rejects_a_blank_value(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptTimeZone", "  ", "scriptTimeZone");
    }

    @Test
    public void test_script_locale_rejects_a_malformed_tag(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptLocale", "not a locale", "scriptLocale");
    }

    @Test
    public void test_script_locale_rejects_a_blank_value(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptLocale", "", "scriptLocale");
    }

    @Test
    public void test_invalid_script_sandbox_values(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptsEnabled", "maybe", "scriptsEnabled");
        assertHasError(tempDir, "scriptTextImportEnabled", "maybe", "scriptTextImportEnabled");
        assertHasError(tempDir, "scriptInstructionBudget", "0", "scriptInstructionBudget");
        assertHasError(tempDir, "scriptInstructionBudget", "not-a-number", "scriptInstructionBudget");
        assertHasError(tempDir, "scriptTimeoutMs", "0", "scriptTimeoutMs");
        assertHasError(tempDir, "scriptMaxDepth", "0", "scriptMaxDepth");
        assertHasError(tempDir, "scriptMaxSourceBytes", "nonsense", "scriptMaxSourceBytes");
        assertHasError(tempDir, "scriptMaxSourceBytes", "0", "scriptMaxSourceBytes");
        assertHasError(tempDir, "scriptMaxLogLines", "0", "scriptMaxLogLines");
        assertHasError(tempDir, "scriptMaxLogLineChars", "0", "scriptMaxLogLineChars");
        assertHasError(tempDir, "scriptMaxMemoryBytes", "nonsense", "scriptMaxMemoryBytes");
        assertHasError(tempDir, "scriptMaxMemoryBytes", "0", "scriptMaxMemoryBytes");
    }

    @Test
    public void test_invalid_script_result_and_cursor_bounds(@TempDir Path tempDir) {
        assertHasError(tempDir, "scriptMaxResultBytes", "nonsense", "scriptMaxResultBytes");
        assertHasError(tempDir, "scriptMaxResultBytes", "0", "scriptMaxResultBytes");
        assertHasError(tempDir, "scriptCursorBatchSize", "0", "scriptCursorBatchSize");
        assertHasError(tempDir, "scriptCursorMaxBatchSize", "0", "scriptCursorMaxBatchSize");
    }

    @Test
    public void test_cursor_batch_size_must_not_exceed_the_maximum(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("scriptCursorBatchSize", "5001");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("must not be greater than scriptCursorMaxBatchSize")));
    }

    @Test
    public void test_scripts_enabled_true_is_valid(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("scriptsEnabled", "true");
        config.put("scriptTextImportEnabled", "true");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }
}
