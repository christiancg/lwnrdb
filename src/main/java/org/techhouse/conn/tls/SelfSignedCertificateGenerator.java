package org.techhouse.conn.tls;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import org.techhouse.config.Globals;
import org.techhouse.ex.TlsConfigurationException;

/**
 * Generates a self-signed RSA certificate and stores it in a PKCS12 keystore, using only public JDK
 * APIs. The X.509 structure is hand-encoded with {@link DerWriter} because the JDK exposes no public
 * builder for {@code X509Certificate}, and the project forbids third-party libraries. The result is a
 * development-only certificate; production deployments should supply a CA-issued keystore.
 */
public final class SelfSignedCertificateGenerator {
    // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
    private static final int[] OID_SHA256_WITH_RSA = {1, 2, 840, 113549, 1, 1, 11};
    // commonName: 2.5.4.3
    private static final int[] OID_COMMON_NAME = {2, 5, 4, 3};
    // basicConstraints: 2.5.29.19
    private static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    // subjectAltName: 2.5.29.17
    private static final int[] OID_SUBJECT_ALT_NAME = {2, 5, 29, 17};
    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;
    private static final int VERSION_V3 = 2;
    private static final int SERIAL_BITS = 159;

    private SelfSignedCertificateGenerator() {
    }

    /**
     * Generates a self-signed certificate, stores it under {@code alias} in a new PKCS12 keystore,
     * persists that keystore to {@code keystorePath}, and returns the in-memory keystore.
     */
    public static KeyStore generate(Path keystorePath, char[] password, String alias) {
        try {
            final var keyPair = generateKeyPair();
            final var certificate = buildSelfSignedCertificate(keyPair);
            final var keyStore = KeyStore.getInstance(Globals.TLS_KEYSTORE_TYPE);
            keyStore.load(null, null);
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[]{certificate});
            persist(keyStore, keystorePath, password);
            return keyStore;
        } catch (GeneralSecurityException | IOException e) {
            throw new TlsConfigurationException("Failed to generate a self-signed TLS certificate at " + keystorePath,
                    e);
        }
    }

    private static KeyPair generateKeyPair() throws GeneralSecurityException {
        final var generator = KeyPairGenerator.getInstance(Globals.TLS_KEY_ALGORITHM);
        generator.initialize(Globals.TLS_KEY_SIZE, new SecureRandom());
        return generator.generateKeyPair();
    }

    private static Certificate buildSelfSignedCertificate(KeyPair keyPair) throws GeneralSecurityException {
        final var sigAlgId = DerWriter.sequence(DerWriter.oid(OID_SHA256_WITH_RSA), DerWriter.nullValue());
        final var name = encodeCommonName();
        final var now = Instant.now();
        final var notBefore = now.minus(Duration.ofHours(1));
        final var notAfter = now.plus(Duration.ofDays(Globals.TLS_CERT_VALIDITY_DAYS));
        final var validity = DerWriter.sequence(DerWriter.utcTime(notBefore), DerWriter.utcTime(notAfter));
        // publicKey.getEncoded() is already a DER-encoded SubjectPublicKeyInfo, so it is embedded verbatim.
        final var subjectPublicKeyInfo = keyPair.getPublic().getEncoded();
        final var serial = new BigInteger(SERIAL_BITS, new SecureRandom()).add(BigInteger.ONE);

        final var tbsCertificate = DerWriter.sequence(
                DerWriter.explicit(0, DerWriter.integer(BigInteger.valueOf(VERSION_V3))), DerWriter.integer(serial),
                sigAlgId, name, validity, name, subjectPublicKeyInfo,
                DerWriter.explicit(3, DerWriter.sequence(basicConstraintsExtension(), subjectAltNameExtension())));

        final var signature = sign(tbsCertificate, keyPair);
        final var certificate = DerWriter.sequence(tbsCertificate, sigAlgId, DerWriter.bitString(signature));

        final var factory = CertificateFactory.getInstance("X.509");
        return factory.generateCertificate(new ByteArrayInputStream(certificate));
    }

    private static byte[] encodeCommonName() {
        return DerWriter.sequence(DerWriter
                .set(DerWriter.sequence(DerWriter.oid(OID_COMMON_NAME), DerWriter.utf8String(Globals.TLS_CERT_DNAME))));
    }

    private static byte[] basicConstraintsExtension() {
        // An empty SEQUENCE means cA defaults to FALSE: an end-entity certificate.
        final var extnValue = DerWriter.octetString(DerWriter.sequence());
        return DerWriter.sequence(DerWriter.oid(OID_BASIC_CONSTRAINTS), DerWriter.tlv(0x01, new byte[]{(byte) 0xFF}),
                extnValue);
    }

    private static byte[] subjectAltNameExtension() {
        final var dnsName = DerWriter.contextPrimitive(SAN_DNS_NAME, "localhost".getBytes(StandardCharsets.US_ASCII));
        final var ipAddress = DerWriter.contextPrimitive(SAN_IP_ADDRESS, new byte[]{127, 0, 0, 1});
        final var generalNames = DerWriter.sequence(dnsName, ipAddress);
        return DerWriter.sequence(DerWriter.oid(OID_SUBJECT_ALT_NAME), DerWriter.octetString(generalNames));
    }

    private static byte[] sign(byte[] data, KeyPair keyPair) throws GeneralSecurityException {
        final var signature = Signature.getInstance(Globals.TLS_SIGNATURE_ALGORITHM);
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        return signature.sign();
    }

    private static void persist(KeyStore keyStore, Path keystorePath, char[] password)
            throws GeneralSecurityException, IOException {
        final var parent = keystorePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (var out = Files.newOutputStream(keystorePath)) {
            keyStore.store(out, password);
        }
    }
}
