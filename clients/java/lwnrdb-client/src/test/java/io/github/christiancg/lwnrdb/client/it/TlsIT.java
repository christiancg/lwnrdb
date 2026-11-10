package io.github.christiancg.lwnrdb.client.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.RetryPolicy;
import io.github.christiancg.lwnrdb.client.TlsSettings;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TlsIT {
    private static final char[] STORE_PASSWORD = "change_it".toCharArray();
    private ServerProcess server;

    @BeforeAll
    public void startServer() {
        server = ServerProcess.start(Map.of("tlsEnabled", "true", "tlsKeystorePath", "certs/lwnrdb.p12",
                "tlsKeystorePassword", "change_it"));
    }

    @AfterAll
    public void stopServer() throws Exception {
        server.close();
    }

    private Path exportedTrustStore() throws Exception {
        final var keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(server.workDir().resolve("certs/lwnrdb.p12"))) {
            keystore.load(in, STORE_PASSWORD);
        }
        final var trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        final var alias = keystore.aliases().nextElement();
        trust.setCertificateEntry("lwnrdb", keystore.getCertificate(alias));
        final var path = Files.createTempFile("lwnrdb-trust", ".p12");
        try (OutputStream out = Files.newOutputStream(path)) {
            trust.store(out, STORE_PASSWORD);
        }
        return path;
    }

    @Test
    public void test_an_insecure_client_connects_over_tls() {
        try (var client = LwnrdbClient.connect(server.settings().tls(TlsSettings.insecure()).build())) {
            assertTrue(client.serverInfo().state().isPresent());
        }
    }

    @Test
    public void test_a_client_trusting_the_exported_certificate_connects() throws Exception {
        final var trust = exportedTrustStore();
        try (var client = LwnrdbClient
                .connect(server.settings().tls(TlsSettings.trustStore(trust, STORE_PASSWORD)).build())) {
            assertTrue(client.serverInfo().version().isPresent());
        }
    }

    @Test
    public void test_system_trust_and_plaintext_clients_are_refused() {
        assertThrows(LwnrdbException.class,
                () -> LwnrdbClient.connect(server.settings().tls(TlsSettings.systemTrust()).build()));
        assertThrows(LwnrdbException.class, () -> LwnrdbClient.connect(
                server.settings().requestTimeout(Duration.ofSeconds(5)).retryPolicy(RetryPolicy.none()).build()));
    }
}
