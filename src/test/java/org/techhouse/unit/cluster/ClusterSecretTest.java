package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterSecret;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class ClusterSecretTest {
    private static final String SECRET = "a-cluster-secret-of-thirty-two-c";
    private final Configuration config = Configuration.getInstance();
    private String origSecret;

    @BeforeEach
    public void setUp() throws Exception {
        origSecret = config.getClusterSecret();
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
    }

    @Test
    public void matchesAnEqualSecret() {
        assertTrue(ClusterSecret.matches(new String(SECRET.toCharArray())));
    }

    @Test
    public void rejectsADifferentSecret() {
        assertFalse(ClusterSecret.matches("b-cluster-secret-of-thirty-two-c"));
    }

    @Test
    public void rejectsAPrefixAndASuffixOfTheSecret() {
        assertFalse(ClusterSecret.matches(SECRET.substring(0, SECRET.length() - 1)));
        assertFalse(ClusterSecret.matches(SECRET.substring(1)));
        assertFalse(ClusterSecret.matches(SECRET + "x"));
        assertFalse(ClusterSecret.matches(""));
    }

    @Test
    public void rejectsNull() {
        assertFalse(ClusterSecret.matches(null));
    }

    @Test
    public void rejectsEverythingWhenNoSecretIsConfigured() throws Exception {
        TestUtils.setPrivateField(config, "clusterSecret", null);
        assertFalse(ClusterSecret.matches(SECRET));
    }

    @Test
    public void comparesUtf8BytesSoAMultiByteSecretMatches() throws Exception {
        final var multiByte = "ñandú-señal-secreta-de-clúster-€";
        TestUtils.setPrivateField(config, "clusterSecret", multiByte);
        assertTrue(ClusterSecret.matches("ñandú-señal-secreta-de-clúster-€"));
        assertFalse(ClusterSecret.matches("nandu-senal-secreta-de-cluster-E"));
    }
}
