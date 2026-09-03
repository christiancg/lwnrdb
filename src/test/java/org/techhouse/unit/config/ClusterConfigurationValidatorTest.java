package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.ConfigurationValidator;

public class ClusterConfigurationValidatorTest {

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
        map.put("clusterEnabled", "true");
        map.put("clusterPort", "9990");
        map.put("clusterBindAddress", "0.0.0.0");
        map.put("clusterAdvertisedAddress", "127.0.0.1");
        map.put("clusterSeeds", "host1:9990,host2:9991");
        map.put("nodeId", "");
        map.put("clusterExpectedSize", "3");
        map.put("gossipIntervalMs", "1000");
        map.put("suspectTimeoutMs", "5000");
        map.put("deadTimeoutMs", "15000");
        map.put("replicationAckTimeoutMs", "5000");
        map.put("virtualNodesPerNode", "128");
        map.put("readFallbackToLocal", "true");
        map.put("scriptRoutingEnabled", "true");
        map.put("clusterTlsEnabled", "false");
        map.put("clusterSecret", "shared-secret");
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
        map.put("schedulesEnabled", "false");
        map.put("scheduleThreads", "2");
        map.put("scheduleQueueSize", "100");
        map.put("scheduleTickMs", "1000");
        map.put("scheduleRefreshMs", "60000");
        map.put("scheduleTimeoutMs", "30000");
        map.put("scheduleMaxPerDatabase", "100");
        map.put("scheduleCacheMaxBytes", "8Mb");
        map.put("scriptTextImportEnabled", "false");
        map.put("scriptProcedureImportEnabled", "true");
        return map;
    }

    private static void assertError(Path tempDir, String key, String value, String fragment) {
        final var config = baseValid(tempDir);
        config.put(key, value);
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)),
                "Expected error mentioning '" + fragment + "' for " + key + "=" + value + ", got: " + errors);
    }

    @Test
    public void test_valid_enabled_cluster_has_no_errors(@TempDir Path tempDir) {
        assertTrue(ConfigurationValidator.validate(baseValid(tempDir)).isEmpty());
    }

    @Test
    public void test_empty_seeds_allowed_for_first_node(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("clusterSeeds", "");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_invalid_booleans(@TempDir Path tempDir) {
        assertError(tempDir, "clusterEnabled", "maybe", "clusterEnabled");
        assertError(tempDir, "clusterTlsEnabled", "maybe", "clusterTlsEnabled");
        assertError(tempDir, "readFallbackToLocal", "maybe", "readFallbackToLocal");
        assertError(tempDir, "scriptRoutingEnabled", "maybe", "scriptRoutingEnabled");
    }

    @Test
    public void test_invalid_cluster_port(@TempDir Path tempDir) {
        assertError(tempDir, "clusterPort", "0", "clusterPort");
        assertError(tempDir, "clusterPort", "70000", "clusterPort");
        assertError(tempDir, "clusterPort", "abc", "clusterPort");
    }

    @Test
    public void test_cluster_port_must_differ_from_port(@TempDir Path tempDir) {
        assertError(tempDir, "clusterPort", "8989", "must be different from port");
    }

    @Test
    public void test_invalid_numeric_fields(@TempDir Path tempDir) {
        assertError(tempDir, "clusterExpectedSize", "0", "clusterExpectedSize");
        assertError(tempDir, "virtualNodesPerNode", "0", "virtualNodesPerNode");
        assertError(tempDir, "gossipIntervalMs", "0", "gossipIntervalMs");
        assertError(tempDir, "suspectTimeoutMs", "0", "suspectTimeoutMs");
        assertError(tempDir, "deadTimeoutMs", "0", "deadTimeoutMs");
        assertError(tempDir, "replicationAckTimeoutMs", "0", "replicationAckTimeoutMs");
    }

    @Test
    public void test_dead_timeout_must_exceed_suspect_timeout(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("suspectTimeoutMs", "5000");
        config.put("deadTimeoutMs", "5000");
        final var errors = ConfigurationValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.contains("deadTimeoutMs")));
    }

    @Test
    public void test_required_strings_when_enabled(@TempDir Path tempDir) {
        assertError(tempDir, "clusterSecret", "  ", "clusterSecret");
        assertError(tempDir, "clusterBindAddress", "  ", "clusterBindAddress");
        assertError(tempDir, "clusterAdvertisedAddress", "  ", "clusterAdvertisedAddress");
    }

    @Test
    public void test_invalid_seed_format(@TempDir Path tempDir) {
        assertError(tempDir, "clusterSeeds", "hostonly", "clusterSeeds");
        assertError(tempDir, "clusterSeeds", "host:abc", "clusterSeeds");
        assertError(tempDir, "clusterSeeds", "host:70000", "clusterSeeds");
    }

    @Test
    public void test_disabled_cluster_ignores_required_strings(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("clusterEnabled", "false");
        config.put("clusterSecret", "");
        config.put("clusterBindAddress", "");
        config.put("clusterAdvertisedAddress", "");
        config.put("clusterSeeds", "garbage");
        assertTrue(ConfigurationValidator.validate(config).isEmpty());
    }

    @Test
    public void test_disabled_cluster_still_validates_numeric_parseability(@TempDir Path tempDir) {
        final var config = baseValid(tempDir);
        config.put("clusterEnabled", "false");
        config.put("clusterPort", "not-a-number");
        final var errors = ConfigurationValidator.validate(config);
        assertFalse(errors.isEmpty());
    }
}
