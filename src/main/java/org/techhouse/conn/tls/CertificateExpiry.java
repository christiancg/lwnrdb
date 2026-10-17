package org.techhouse.conn.tls;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.techhouse.log.Logger;

public final class CertificateExpiry {
    public static final Duration WARNING_WINDOW = Duration.ofDays(30);
    private static final Logger logger = Logger.logFor(CertificateExpiry.class);

    private CertificateExpiry() {
    }

    public static void warnIfExpiringSoon(KeyStore keyStore, Path keystorePath) {
        try {
            for (final var notice : expiringWithinWindow(keyStore, Instant.now())) {
                logger.warning("SECURITY WARNING: in the TLS keystore at " + keystorePath + ", " + notice
                        + ". Renew it before clients and peers start refusing the handshake.");
            }
        } catch (KeyStoreException e) {
            logger.warning("Could not read the certificates in the TLS keystore at " + keystorePath
                    + " to check their expiry: " + e.getMessage());
        }
    }

    public static List<String> expiringWithinWindow(KeyStore keyStore, Instant now) throws KeyStoreException {
        final var notices = new ArrayList<String>();
        final var horizon = now.plus(WARNING_WINDOW);
        for (final var alias : Collections.list(keyStore.aliases())) {
            if (keyStore.getCertificate(alias) instanceof X509Certificate certificate) {
                final var notAfter = certificate.getNotAfter().toInstant();
                if (notAfter.isBefore(now)) {
                    notices.add("the certificate '" + alias + "' expired at " + notAfter);
                } else if (notAfter.isBefore(horizon)) {
                    notices.add("the certificate '" + alias + "' expires at " + notAfter);
                }
            }
        }
        return notices;
    }
}
