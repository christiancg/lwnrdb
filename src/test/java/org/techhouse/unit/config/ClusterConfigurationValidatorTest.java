package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.ConfigurationValidator;
import org.techhouse.test.ConfigFixture;

public class ClusterConfigurationValidatorTest {
    private static Map<String, String> baseValid(Path writablePath) {
        return ConfigFixture.clusterEnabled(writablePath);
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
        assertError(tempDir, "scriptLocalityWeight", "101", "scriptLocalityWeight");
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
