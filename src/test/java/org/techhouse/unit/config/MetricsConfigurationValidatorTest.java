package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.ConfigurationValidator;
import org.techhouse.test.ConfigFixture;

public class MetricsConfigurationValidatorTest {

    private static List<String> metricsErrors(Map<String, String> config) {
        return ConfigurationValidator.validate(config).stream().filter(error -> error.contains("metrics")).toList();
    }

    @Test
    public void test_the_shipped_defaults_are_valid(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.remove("metricsPort");
        config.remove("metricsBindAddress");
        config.remove("metricsToken");

        assertEquals(List.of(), metricsErrors(config));
    }

    @Test
    public void test_a_metrics_port_equal_to_the_client_port_is_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("port", "9100");
        config.put("metricsPort", "9100");

        assertEquals(List.of("metricsPort (9100) must be different from port (9100)"), metricsErrors(config));
    }

    @Test
    public void test_a_metrics_port_of_zero_never_collides(@TempDir Path tempDir) {
        final var config = ConfigFixture.clusterEnabled(tempDir);
        config.put("metricsPort", "0");
        config.put("clusterPort", "0");

        assertTrue(metricsErrors(config).isEmpty(), metricsErrors(config)::toString);
    }

    @Test
    public void test_the_cluster_port_collides_only_with_clustering_on(@TempDir Path tempDir) {
        final var clustered = ConfigFixture.clusterEnabled(tempDir);
        clustered.put("clusterPort", "9101");
        clustered.put("metricsPort", "9101");
        final var standalone = ConfigFixture.fullValid(tempDir);
        standalone.put("clusterPort", "9101");
        standalone.put("metricsPort", "9101");

        assertEquals(List.of("metricsPort (9101) must be different from clusterPort (9101)"), metricsErrors(clustered));
        assertEquals(List.of(), metricsErrors(standalone));
    }

    @Test
    public void test_out_of_range_and_unparseable_values_are_refused(@TempDir Path tempDir) {
        final var config = ConfigFixture.fullValid(tempDir);
        config.put("metricsPort", "65536");
        config.put("metricsBindAddress", "not an address!");

        final var errors = metricsErrors(config);

        assertTrue(errors.stream().anyMatch(error -> error.startsWith("metricsPort ")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.startsWith("metricsBindAddress ")), errors::toString);
    }
}
