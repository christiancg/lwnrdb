package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.mockito.MockedStatic;
import org.techhouse.StartupWarnings;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class StartupWarningsTest {
    private final Configuration config = Configuration.getInstance();
    private String origTlsEnabled;
    private String origKeystorePassword;
    private String origKeystorePath;
    private String origFilePath;
    private String origLogPath;
    private String origFetchEnabled;
    private String origAllowlist;
    private String origMaxMemory;
    private String origScriptsEnabled;
    private String origFaultInjection;
    private String origSyncWrites;

    @BeforeEach
    public void setUp() {
        origTlsEnabled = String.valueOf(config.isTlsEnabled());
        origKeystorePassword = config.getTlsKeystorePassword();
        origKeystorePath = config.getTlsKeystorePath();
        origFilePath = config.getFilePath();
        origLogPath = config.getLogPath();
        origFetchEnabled = String.valueOf(config.isScriptFetchEnabled());
        origAllowlist = String.join(",", config.getScriptFetchAllowlist());
        origMaxMemory = String.valueOf(config.getMaxMemoryBytes());
        origScriptsEnabled = String.valueOf(config.isScriptsEnabled());
        origFaultInjection = String.valueOf(config.isFaultInjectionEnabled());
        origSyncWrites = String.valueOf(config.isSyncWrites());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", origTlsEnabled);
        TestUtils.setPrivateField(config, "tlsKeystorePassword", origKeystorePassword);
        TestUtils.setPrivateField(config, "tlsKeystorePath", origKeystorePath);
        TestUtils.setPrivateField(config, "filePath", origFilePath);
        TestUtils.setPrivateField(config, "logPath", origLogPath);
        TestUtils.setPrivateField(config, "scriptFetchEnabled", origFetchEnabled);
        TestUtils.setPrivateField(config, "scriptFetchAllowlist", origAllowlist);
        TestUtils.setPrivateField(config, "maxMemory", origMaxMemory);
        TestUtils.setPrivateField(config, "scriptsEnabled", origScriptsEnabled);
        TestUtils.setPrivateField(config, "faultInjectionEnabled", origFaultInjection);
        TestUtils.setPrivateField(config, "syncWrites", origSyncWrites);
    }

    @Test
    public void test_a_wildcard_fetch_allowlist_is_warned_about() throws Exception {
        enableFetch("*");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            assertLogged(logWriter, "169.254.169.254");
            assertLogged(logWriter, "any PUBLIC host");
            assertNotLogged(logWriter, "SECURITY WARNING");
        }
    }

    @Test
    public void test_an_exact_private_entry_is_a_security_warning() throws Exception {
        enableFetch("*, 10.0.0.5, Localhost, [::1], api.example.com, 8.8.8.8");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            assertLogged(logWriter, "SECURITY WARNING: scriptFetchAllowlist names 10.0.0.5, Localhost, [::1] exactly");
        }
    }

    @Test
    public void test_an_empty_fetch_allowlist_is_warned_about_as_useless() throws Exception {
        enableFetch("");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            assertLogged(logWriter, "every fetch will be refused");
        }
    }

    @Test
    public void test_a_named_fetch_allowlist_is_reported_as_information() throws Exception {
        enableFetch("example.com");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            assertLogged(logWriter, "Script fetch is enabled for: example.com");
            assertNotLogged(logWriter, "SECURITY WARNING");
        }
    }

    @Test
    public void test_disabled_fetch_says_nothing() throws Exception {
        TestUtils.setPrivateField(config, "scriptFetchEnabled", "false");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_budgets_totalling_more_than_the_heap_are_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "maxMemory", "1024Gb");
        TestUtils.setPrivateField(config, "scriptsEnabled", "false");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfCachesExceedHeap();
            assertLogged(logWriter, "cannot fit in heap");
        }
    }

    @Test
    public void test_an_xmx_far_above_maxMemory_is_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "maxMemory", "1Mb");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfXmxExceedsMaxMemory();
            assertLogged(logWriter, "more than 2x the configured maxMemory");
            assertLogged(logWriter, "keep maxMemory around half of -Xmx");
        }
    }

    @Test
    public void test_an_unlimited_cache_skips_the_xmx_comparison() throws Exception {
        TestUtils.setPrivateField(config, "maxMemory", String.valueOf(Globals.CACHE_UNLIMITED));
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfXmxExceedsMaxMemory();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_enabled_fault_injection_is_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "faultInjectionEnabled", "true");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfFaultInjectionEnabled();
            assertLogged(logWriter, "faultInjectionEnabled=true");
        }
    }

    @Test
    public void test_warns_when_writes_are_not_synced() throws Exception {
        TestUtils.setPrivateField(config, "syncWrites", "false");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfWritesAreNotSynced();
            assertLogged(logWriter, "DURABILITY WARNING: syncWrites=false");
        }
    }

    @Test
    public void test_silent_when_writes_are_synced() throws Exception {
        TestUtils.setPrivateField(config, "syncWrites", "true");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfWritesAreNotSynced();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_plaintext_clients_are_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfPlaintextClients();
            assertLogged(logWriter, "SECURITY WARNING: tlsEnabled=false");
        }
    }

    @Test
    public void test_no_plaintext_warning_when_tls_is_enabled() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "true");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfPlaintextClients();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_default_keystore_password_is_warned_about_when_tls_is_on() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "true");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultKeystorePassword();
            assertLogged(logWriter, "tlsKeystorePassword is still the shipped value 'change_it'");
        }
    }

    @Test
    public void test_default_keystore_password_is_not_warned_about_when_tls_is_off() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultKeystorePassword();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_custom_keystore_password_is_not_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "tlsEnabled", "true");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "a-better-secret");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultKeystorePassword();
            logWriter.verifyNoInteractions();
        }
    }

    @Test
    public void test_group_readable_data_root_is_warned_about(@TempDir Path tmp) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        final var dataRoot = folderWithMode(tmp, "data", "rwxr-xr-x");
        final var logs = folderWithMode(tmp, "logs", "rwx------");
        final var keystore = Files.createFile(tmp.resolve("lwnrdb.p12"));
        Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-r--r--"));
        TestUtils.setPrivateField(config, "filePath", dataRoot.toString());
        TestUtils.setPrivateField(config, "logPath", logs.toString());
        TestUtils.setPrivateField(config, "tlsEnabled", "true");
        TestUtils.setPrivateField(config, "tlsKeystorePath", keystore.toString());
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfStorageIsReadableByOthers();
            assertLogged(logWriter, "data folder (filePath) at " + dataRoot.toAbsolutePath());
            assertLogged(logWriter, "chmod 700 " + dataRoot.toAbsolutePath());
            assertLogged(logWriter, "chmod 600 " + keystore.toAbsolutePath());
            assertNotLogged(logWriter, logs.toAbsolutePath().toString());
        }
    }

    @Test
    public void test_owner_only_data_root_is_not_warned_about(@TempDir Path tmp) throws Exception {
        assumeTrue(OwnerOnlyFiles.supported());
        final var dataRoot = folderWithMode(tmp, "data", "rwx------");
        TestUtils.setPrivateField(config, "filePath", dataRoot.toString());
        TestUtils.setPrivateField(config, "logPath", dataRoot.toString());
        TestUtils.setPrivateField(config, "tlsEnabled", "false");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfStorageIsReadableByOthers();
            assertNotLogged(logWriter, dataRoot.toAbsolutePath().toString());
        }
    }

    private static Path folderWithMode(Path parent, String name, String mode) throws Exception {
        final var folder = Files.createDirectory(parent.resolve(name));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(mode));
        return folder;
    }

    private static void assertNotLogged(MockedStatic<LogWriter> logWriter, String unexpected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(unexpected))), never());
    }

    private void enableFetch(String allowlist) throws Exception {
        TestUtils.setPrivateField(config, "scriptFetchEnabled", "true");
        TestUtils.setPrivateField(config, "scriptFetchAllowlist", allowlist);
    }

    private static void assertLogged(MockedStatic<LogWriter> logWriter, String expected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))));
    }

    @Test
    public void test_every_warning_is_silent_on_a_sane_configuration() throws Exception {
        TestUtils.setPrivateField(config, "scriptFetchEnabled", "false");
        TestUtils.setPrivateField(config, "faultInjectionEnabled", "false");
        assertTrue(config.getMaxMemoryBytes() > 0, "the fixture must have a bounded cache");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            StartupWarnings.warnIfFaultInjectionEnabled();
            logWriter.verifyNoInteractions();
        }
    }
}
