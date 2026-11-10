package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.ServerAddress;
import io.github.christiancg.lwnrdb.client.TlsSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class TlsSocketFactory {
    private static final System.Logger LOGGER = System.getLogger(TlsSocketFactory.class.getName());
    private static final String TLS = "TLS";
    private static final String HOSTNAME_CHECK = "HTTPS";

    private final Optional<TlsSettings> tls;
    private final Duration connectTimeout;
    private final SSLContext context;

    public TlsSocketFactory(Optional<TlsSettings> tls, Duration connectTimeout) {
        this.tls = tls;
        this.connectTimeout = connectTimeout;
        this.context = tls.map(TlsSocketFactory::contextFor).orElse(null);
        if (tls.map(TlsSettings::isInsecure).orElse(false)) {
            LOGGER.log(System.Logger.Level.WARNING, "TLS certificate and hostname checks are disabled");
        }
    }

    public Socket open(ServerAddress address) throws IOException {
        final var socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(address.host(), address.port()),
                    (int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE));
            if (context == null) {
                return socket;
            }
            final var secure = (SSLSocket) context.getSocketFactory().createSocket(socket, address.host(),
                    address.port(), true);
            if (!tls.orElseThrow().isInsecure()) {
                final var parameters = secure.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm(HOSTNAME_CHECK);
                secure.setSSLParameters(parameters);
            }
            secure.setSoTimeout((int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE));
            secure.startHandshake();
            secure.setSoTimeout(0);
            return secure;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static SSLContext contextFor(TlsSettings settings) {
        try {
            final var context = SSLContext.getInstance(TLS);
            context.init(null, trustManagers(settings), null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("could not set up TLS: " + e.getMessage(), e);
        }
    }

    private static TrustManager[] trustManagers(TlsSettings settings) throws GeneralSecurityException, IOException {
        if (settings.isInsecure()) {
            return new TrustManager[]{new TrustEverything()};
        }
        if (settings.trustStorePath().isEmpty()) {
            return null;
        }
        final var store = KeyStore.getInstance(settings.trustStoreType());
        try (InputStream in = Files.newInputStream(settings.trustStorePath().get())) {
            store.load(in, settings.trustStorePassword());
        }
        final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return factory.getTrustManagers();
    }

    private static final class TrustEverything implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
