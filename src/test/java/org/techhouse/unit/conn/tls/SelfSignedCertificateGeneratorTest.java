package org.techhouse.unit.conn.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.conn.tls.SelfSignedCertificateGenerator;
import org.techhouse.ex.TlsConfigurationException;
import org.techhouse.utils.OwnerOnlyPermissions;

public class SelfSignedCertificateGeneratorTest {

    private static final char[] PASSWORD = "change_it".toCharArray();
    private static final String ALIAS = "lwnrdb";

    @Test
    public void test_generates_loadable_pkcs12_keystore(@TempDir Path tempDir) throws Exception {
        final var keystorePath = tempDir.resolve("certs").resolve("lwnrdb.p12");
        final var keyStore = SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS);

        assertNotNull(keyStore);
        assertTrue(Files.exists(keystorePath), "keystore should be persisted to disk");

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

        assertEquals(certificate.getSubjectX500Principal(), certificate.getIssuerX500Principal());
        certificate.verify(certificate.getPublicKey());
        certificate.checkValidity();

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
        final var blocker = Files.createFile(tempDir.resolve("blocker"));
        final var keystorePath = blocker.resolve("sub").resolve("lwnrdb.p12");
        assertThrows(TlsConfigurationException.class,
                () -> SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS));
    }

    @Test
    public void test_generated_keystore_and_its_folder_are_owner_only(@TempDir Path tempDir) throws Exception {
        assumeTrue(OwnerOnlyPermissions.supported());
        final var certsFolder = tempDir.resolve("certs");
        final var keystorePath = certsFolder.resolve("lwnrdb.p12");
        SelfSignedCertificateGenerator.generate(keystorePath, PASSWORD, ALIAS);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(certsFolder)));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keystorePath)));
    }
}
