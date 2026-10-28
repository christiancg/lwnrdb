package org.techhouse.client;

import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class TlsSockets {
    private static final X509TrustManager TRUST_ANY = new X509TrustManager() {
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
    };

    private TlsSockets() {
    }

    static SSLSocketFactory factory(boolean insecure) {
        if (!insecure) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        try {
            final var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{TRUST_ANY}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new ClientException("cannot set up TLS: " + e.getMessage(), ClientException.UNREACHABLE, e);
        }
    }
}
