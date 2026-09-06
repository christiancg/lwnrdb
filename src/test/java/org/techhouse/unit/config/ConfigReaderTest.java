package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigReader;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;

public class ConfigReaderTest {

    @BeforeEach
    public void setUp() {
        final var originalConfigFile = new File(Globals.FILE_CONFIG_NAME);
        if (originalConfigFile.exists()) {
            if (!originalConfigFile.renameTo(new File(Globals.FILE_CONFIG_NAME + ".moved"))) {
                fail("Could not rename config file");
            }
        }
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        final var movedConfigFile = new File(Globals.FILE_CONFIG_NAME + ".moved");
        if (movedConfigFile.exists()) {
            if (!movedConfigFile.renameTo(new File(Globals.FILE_CONFIG_NAME))) {
                fail("Failed returning to original file");
            }
        }
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        Configuration.getInstance();
    }

    // Successfully loads configuration from default file when no external file is present
    @Test
    public void test_load_configuration_from_default_file() {
        // Arrange
        Map<String, String> expectedConfig = new HashMap<>();
        expectedConfig.put("port", "8989");
        expectedConfig.put("maxConnections", "100");
        expectedConfig.put("filePath", "db");
        expectedConfig.put("backgroundProcessingThreads", "10");
        expectedConfig.put("logPath", "logs");
        expectedConfig.put("maxLogFiles", "7");
        expectedConfig.put("maxPageSize", "2Mb");
        expectedConfig.put("maxEntrySize", "1Mb");
        expectedConfig.put("defaultAdminUsername", "admin");
        expectedConfig.put("defaultAdminPassword", "administrator");
        expectedConfig.put("maxMemory", "512mb");
        expectedConfig.put("transactionLockTimeoutMs", "5000");
        expectedConfig.put("tlsEnabled", "false");
        expectedConfig.put("tlsKeystorePath", "certs/lwnrdb.p12");
        expectedConfig.put("tlsKeystorePassword", "change_it");
        expectedConfig.put("clusterEnabled", "false");
        expectedConfig.put("clusterPort", "9990");
        expectedConfig.put("clusterBindAddress", "0.0.0.0");
        expectedConfig.put("clusterAdvertisedAddress", "127.0.0.1");
        expectedConfig.put("clusterSeeds", "");
        expectedConfig.put("nodeId", "");
        expectedConfig.put("clusterExpectedSize", "1");
        expectedConfig.put("gossipIntervalMs", "1000");
        expectedConfig.put("suspectTimeoutMs", "5000");
        expectedConfig.put("deadTimeoutMs", "15000");
        expectedConfig.put("replicationAckTimeoutMs", "5000");
        expectedConfig.put("virtualNodesPerNode", "128");
        expectedConfig.put("readFallbackToLocal", "true");
        expectedConfig.put("scriptRoutingEnabled", "true");
        expectedConfig.put("clusterTlsEnabled", "false");
        expectedConfig.put("clusterSecret", "");
        expectedConfig.put("antiEntropyIntervalMs", "60000");
        expectedConfig.put("tombstoneRetentionMs", "86400000");
        expectedConfig.put("scriptTimeZone", "UTC");
        expectedConfig.put("scriptLocale", "en-US");
        expectedConfig.put("scriptsEnabled", "true");
        expectedConfig.put("scriptInstructionBudget", "10000000");
        expectedConfig.put("scriptTimeoutMs", "5000");
        expectedConfig.put("scriptMaxDepth", "200");
        expectedConfig.put("scriptMaxSourceBytes", "256Kb");
        expectedConfig.put("scriptMaxLogLines", "1000");
        expectedConfig.put("scriptMaxLogLineChars", "4096");
        expectedConfig.put("scriptMaxMemoryBytes", "64Mb");
        expectedConfig.put("scriptMaxResultBytes", "16Mb");
        expectedConfig.put("scriptCursorBatchSize", "500");
        expectedConfig.put("scriptCursorMaxBatchSize", "5000");
        expectedConfig.put("maxConcurrentScripts", "16");
        expectedConfig.put("scriptQueueWaitMs", "250");
        expectedConfig.put("maxConcurrentScriptsPerUser", "0");
        expectedConfig.put("maxConcurrentScriptsPerDatabase", "0");
        expectedConfig.put("scriptCompiledCacheSize", "128");
        expectedConfig.put("scriptRunHistoryEnabled", "true");
        expectedConfig.put("scriptRunHistoryKinds", "CALL_PROCEDURE,TRIGGER,SCHEDULE");
        expectedConfig.put("scriptRunHistoryRetentionMs", "604800000");
        expectedConfig.put("scriptRunHistoryIncludeLogs", "false");
        expectedConfig.put("scriptRunHistoryMaxErrorChars", "2000");
        expectedConfig.put("scriptFetchEnabled", "true");
        expectedConfig.put("scriptFetchAllowlist", "*");
        expectedConfig.put("scriptFetchTimeoutMs", "5000");
        expectedConfig.put("scriptFetchMaxResponseBytes", "1Mb");
        expectedConfig.put("procedureCacheSize", "128");
        expectedConfig.put("triggersEnabled", "false");
        expectedConfig.put("triggerThreads", "2");
        expectedConfig.put("triggerQueueSize", "10000");
        expectedConfig.put("triggerMaxDepth", "3");
        expectedConfig.put("triggerTimeoutMs", "1000");
        expectedConfig.put("shutdownTimeoutMs", "15000");
        expectedConfig.put("procedureCacheMaxBytes", "32Mb");
        expectedConfig.put("schemaCacheMaxBytes", "32Mb");
        expectedConfig.put("triggerCacheMaxEntries", "4096");
        expectedConfig.put("metadataMissCacheMaxEntries", "4096");
        expectedConfig.put("triggerRunLogEnabled", "true");
        expectedConfig.put("triggerRunRetentionMs", "86400000");
        expectedConfig.put("triggerMaxAttempts", "3");
        expectedConfig.put("triggerRetryBackoffMs", "1000");
        expectedConfig.put("triggerRetryMaxBackoffMs", "60000");
        expectedConfig.put("triggerDeadLetterRetentionMs", "604800000");
        expectedConfig.put("beforeHookInstructionBudget", "200000");
        expectedConfig.put("beforeHookTimeoutMs", "200");
        expectedConfig.put("schedulesEnabled", "true");
        expectedConfig.put("scheduleThreads", "2");
        expectedConfig.put("scheduleQueueSize", "100");
        expectedConfig.put("scheduleTickMs", "1000");
        expectedConfig.put("scheduleRefreshMs", "60000");
        expectedConfig.put("scheduleTimeoutMs", "30000");
        expectedConfig.put("scheduleMaxPerDatabase", "100");
        expectedConfig.put("scheduleCacheMaxBytes", "8Mb");
        expectedConfig.put("scriptTextImportEnabled", "false");
        expectedConfig.put("scriptProcedureImportEnabled", "true");
        expectedConfig.put("aggregationScriptInstructionBudget", "1000000");
        expectedConfig.put("aggregationScriptTimeoutMs", "2000");
        expectedConfig.put("aggregationScriptMaxSourceBytes", "16Kb");

        // Act
        Map<String, String> actualConfig = ConfigReader.loadConfiguration();

        // Assert
        assertEquals(expectedConfig, actualConfig);
    }

    // TLS keys missing from lwnrdb.cfg fall back to the bundled defaults
    @Test
    public void test_tls_keys_fall_back_to_defaults_when_missing() throws IOException {
        String configContent = "port=8989\nmaxConnections=100\n";
        File configFile = new File(
                Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            Files.writeString(configFile.toPath(), configContent);
            var config = ConfigReader.loadConfiguration();
            assertEquals("false", config.get("tlsEnabled"));
            assertEquals("certs/lwnrdb.p12", config.get("tlsKeystorePath"));
            assertEquals("change_it", config.get("tlsKeystorePassword"));
        } finally {
            final var deleted = configFile.delete();
            assertTrue(deleted);
        }
    }

    // Handles empty configuration files gracefully
    @Test
    public void test_handle_empty_configuration_file() {
        // Arrange
        File configFile = new File(
                Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            try {
                Files.write(configFile.toPath(), Collections.emptyList(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                fail("Failed to create empty configuration file for testing.");
            }

            // Act
            Map<String, String> actualConfig = ConfigReader.loadConfiguration();

            // Assert
            assertNotNull(actualConfig);
            assertFalse(actualConfig.isEmpty()); //should have defaults
        } finally {
            if (!configFile.delete()) {
                fail("Failed to delete empty configuration file.");
            }
        }
    }

    // Config file with a line missing '=' triggers warning branch (L74)
    @Test
    public void test_config_file_with_invalid_property_line() throws IOException {
        String configContent = "port=8989\nnot_a_valid_property\nmaxConnections=100\n";
        File configFile = new File(
                Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            Files.writeString(configFile.toPath(), configContent);
            var config = ConfigReader.loadConfiguration();
            assertNotNull(config);
            assertEquals("8989", config.get("port"));
        } finally {
            final var deleted = configFile.delete();
            assertTrue(deleted);
        }
    }

    // Comment lines (starting with '#') and blank lines are ignored
    @Test
    public void test_config_file_ignores_comments_and_blank_lines() throws IOException {
        String configContent = "# this is a comment\n\nport=8989\n   # indented comment\nmaxConnections=100\n";
        File configFile = new File(
                Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            Files.writeString(configFile.toPath(), configContent);
            var config = ConfigReader.loadConfiguration();
            assertNotNull(config);
            assertEquals("8989", config.get("port"));
            assertEquals("100", config.get("maxConnections"));
            assertFalse(config.containsKey("# this is a comment"));
        } finally {
            final var deleted = configFile.delete();
            assertTrue(deleted);
        }
    }

    // Values containing '=' are preserved (split with limit 2)
    @Test
    public void test_config_file_value_with_equals_sign() throws IOException {
        String configContent = "defaultAdminPassword=ab=cd=ef\n";
        File configFile = new File(
                Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            Files.writeString(configFile.toPath(), configContent);
            var config = ConfigReader.loadConfiguration();
            assertNotNull(config);
            assertEquals("ab=cd=ef", config.get("defaultAdminPassword"));
        } finally {
            final var deleted = configFile.delete();
            assertTrue(deleted);
        }
    }
}
