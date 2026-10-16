package org.techhouse.unit;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.StartupWarnings;
import org.techhouse.config.Configuration;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class StartupWarningsClusterTest {
    private final Configuration config = Configuration.getInstance();
    private boolean origClusterEnabled;
    private boolean origClusterTls;
    private String origTlsEnabled;
    private String origKeystorePath;
    private String origKeystorePassword;

    @BeforeEach
    public void setUp() {
        origClusterEnabled = config.isClusterEnabled();
        origClusterTls = config.isClusterTlsEnabled();
        origTlsEnabled = String.valueOf(config.isTlsEnabled());
        origKeystorePath = config.getTlsKeystorePath();
        origKeystorePassword = config.getTlsKeystorePassword();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origClusterEnabled);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", origClusterTls);
        TestUtils.setPrivateField(config, "tlsEnabled", origTlsEnabled);
        TestUtils.setPrivateField(config, "tlsKeystorePath", origKeystorePath);
        TestUtils.setPrivateField(config, "tlsKeystorePassword", origKeystorePassword);
    }

    private void readableKeystore(Path dir) throws Exception {
        final var keystore = Files.writeString(dir.resolve("cluster.p12"), "keystore");
        Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-r--r--"));
        TestUtils.setPrivateField(config, "tlsKeystorePath", keystore.toString());
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
    }

    @Test
    public void warnsAboutAReadableKeystoreUsedOnlyForClusterTls(@TempDir Path dir) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        readableKeystore(dir);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", true);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfStorageIsReadableByOthers();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("TLS keystore"))));
        }
    }

    @Test
    public void ignoresTheKeystoreWhenNoTlsUsesIt(@TempDir Path dir) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        readableKeystore(dir);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfStorageIsReadableByOthers();
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("TLS keystore"))), never());
        }
    }

    @Test
    public void test_default_keystore_password_warns_for_cluster_tls_only() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", true);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultKeystorePassword();
            logWriter.verify(() -> LogWriter
                    .writeLogEntry(argThat(entry -> entry.contains("tlsKeystorePassword is still the shipped value"))));
        }
    }

    @Test
    public void test_default_keystore_password_is_silent_when_no_tls_uses_it() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultKeystorePassword();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void warnsWhenClusteredWithoutTls() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfClusterWithoutTls();
            logWriter.verify(() -> LogWriter
                    .writeLogEntry(argThat(entry -> entry.contains("SECURITY WARNING: clusterTlsEnabled=false"))));
        }
    }

    @Test
    public void silentWhenClusterTlsEnabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", true);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfClusterWithoutTls();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void silentWhenNotClustered() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfClusterWithoutTls();
            logWriter.verifyNoInteractions();
        }
    }
}
