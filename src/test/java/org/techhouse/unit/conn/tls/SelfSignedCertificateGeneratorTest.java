package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.ex.TlsConfigurationException;

public class SelfSignedCertificateGeneratorTest {

    private static final char[] PASSWORD = "change_it".toCharArray();
    private static final String ALIAS = "lwnrdb";

    @Test
    public void test_generates_loadable_pkcs12_keystore(@TempDir Path tempDir) throws Exception {
        final var keystorePath = tempDir.resolve("certs").resolve("lwnrdb.p12");
        final var keyStore = SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS);

        assertNotNull(keyStore);
        assertTrue(Files.exists(keystorePath), "keystore should be persisted to disk");

        // Reload from disk to confirm it is a valid, readable PKCS12 keystore.
        final var reloaded = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystorePath)) {
            reloaded.load(in, PASSWORD);
        }
        assertTrue(reloaded.containsAlias(ALIAS));
        assertInstanceOf(PrivateKey.class, reloaded.getKey(ALIAS, PASSWORD));
        assertInstanceOf(X509Certificate.class, reloaded.getCertificate(ALIAS));
    }

    @Test
    public void test_generated_certificate_is_self_signed_and_valid(@TempDir Path tempDir) throws Exception {
        final var keystorePath = tempDir.resolve("lwnrdb.p12");
        final var keyStore = SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS);
        final var certificate = (X509Certificate) keyStore.getCertificate(ALIAS);

        // Self-signed: subject equals issuer, and the certificate verifies against its own public key.
        assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();

        // Validity window is roughly one year.
        final var lifetime = Duration.between(certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant());
        assertTrue(lifetime.toDays() >= 364 && lifetime.toDays() <= 366, "expected ~365 day validity");
        assertTrue(certificate.getNotBefore().toInstant().isBefore(Instant.now()));
    }

    @Test
    public void test_generated_certificate_has_subject_alt_names(@TempDir Path tempDir) throws Exception {
        final var keystorePath = tempDir.resolve("lwnrdb.p12");
        final var keyStore = SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS);
        final var certificate = (X509Certificate) keyStore.getCertificate(ALIAS);

        final var sans = certificate.getSubjectAlternativeNames();
        assertNotNull(sans, "certificate should carry subject alternative names");
        assertTrue(sans.stream().anyMatch(san -> "localhost".equals(san.get(1))));
        assertTrue(sans.stream().anyMatch(san -> "127.0.0.1".equals(san.get(1))));
    }

    @Test
    public void test_generate_fails_for_unwritable_path(@TempDir Path tempDir) throws Exception {
        // A regular file in the keystore's path makes directory creation impossible.
        final var blocker = Files.createFile(tempDir.resolve("blocker"));
        final var keystorePath = blocker.resolve("sub").resolve("lwnrdb.p12");
        assertThrows(TlsConfigurationException.class,
                () -> SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS));
    }
}
