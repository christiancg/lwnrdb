package org.techhouse.conn.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ex.TlsConfigurationException;
import org.techhouse.log.Logger;

public final class TlsContextFactory {
    private static final Logger logger = Logger.logFor(TlsContextFactory.class);

    private TlsContextFactory() {
    }

    public static SSLServerSocketFactory createServerSocketFactory(Configuration config) {
        final var keystorePath = Paths.get(config.getTlsKeystorePath());
        final var password = config.getTlsKeystorePassword().toCharArray();
        final var keyStore = loadOrGenerateKeyStore(keystorePath, password);
        try {
            final var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            final var context = SSLContext.getInstance(Globals.TLS_PROTOCOL);
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context.getServerSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new TlsConfigurationException("Failed to initialise the TLS context", e);
        }
    }

    // The keystore is both key and trust material: nodes must share one keystore (or a common CA) to connect.
    public static SSLSocketFactory createSocketFactory(Configuration config) {
        final var keystorePath = Paths.get(config.getTlsKeystorePath());
        final var password = config.getTlsKeystorePassword().toCharArray();
        final var keyStore = loadOrGenerateKeyStore(keystorePath, password);
        try {
            final var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            final var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            final var context = SSLContext.getInstance(Globals.TLS_PROTOCOL);
            context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new TlsConfigurationException("Failed to initialise the TLS client context", e);
        }
    }

    private static KeyStore loadOrGenerateKeyStore(Path keystorePath, char[] password) {
        if (Files.exists(keystorePath)) {
            return loadKeyStore(keystorePath, password);
        }
        logger.warning("SECURITY WARNING: no TLS keystore found at " + keystorePath
                + ". Generating a self-signed certificate for development use only. Clients will not trust it; "
                + "configure tlsKeystorePath to point to a CA-issued PKCS12 keystore before running in production.");
        return SelfSignedCertificateGenerator.generate(keystorePath, password, Globals.TLS_KEY_ALIAS);
    }

    private static KeyStore loadKeyStore(Path keystorePath, char[] password) {
        try (var in = Files.newInputStream(keystorePath)) {
            final var keyStore = KeyStore.getInstance(Globals.TLS_KEYSTORE_TYPE);
            keyStore.load(in, password);
            return keyStore;
        } catch (GeneralSecurityException | IOException e) {
            throw new TlsConfigurationException("Failed to load the TLS keystore at " + keystorePath, e);
        }
    }
}
