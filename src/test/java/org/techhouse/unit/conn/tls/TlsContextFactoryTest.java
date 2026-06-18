package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.ex.TlsConfigurationException;

public class TlsContextFactoryTest {

    private static Configuration mockConfig(Path keystorePath, String password) {
        final var config = mock(Configuration.class);
        when(config.getTlsKeystorePath()).thenReturn(keystorePath.toString());
        when(config.getTlsKeystorePassword()).thenReturn(password);
        return config;
    }

    @Test
    public void test_generates_keystore_when_absent(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("certs").resolve("lwnrdb.p12");
        final var config = mockConfig(keystorePath, "change_it");

        final var factory = TlsContextFactory.createServerSocketFactory(config);

        assertNotNull(factory);
        assertTrue(Files.exists(keystorePath), "a self-signed keystore should have been generated");
    }

    @Test
    public void test_loads_existing_keystore(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("lwnrdb.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "lwnrdb");
        final var config = mockConfig(keystorePath, "change_it");

        final var factory = TlsContextFactory.createServerSocketFactory(config);

        assertNotNull(factory);
    }

    @Test
    public void test_wrong_password_throws(@TempDir Path tempDir) {
        final var keystorePath = tempDir.resolve("lwnrdb.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, "change_it".toCharArray(), "lwnrdb");
        final var config = mockConfig(keystorePath, "wrong-password");

        assertThrows(TlsConfigurationException.class, () -> TlsContextFactory.createServerSocketFactory(config));
    }
}
