package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.StartupWarnings;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class StartupWarningsTest {
    private final Configuration config = Configuration.getInstance();
    private String origPassword;
    private String origFetchEnabled;
    private String origAllowlist;
    private String origMaxMemory;
    private String origScriptsEnabled;

    @BeforeEach
    public void setUp() {
        origPassword = config.getDefaultAdminPassword();
        origFetchEnabled = String.valueOf(config.isScriptFetchEnabled());
        origAllowlist = String.join(",", config.getScriptFetchAllowlist());
        origMaxMemory = String.valueOf(config.getMaxMemoryBytes());
        origScriptsEnabled = String.valueOf(config.isScriptsEnabled());
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", origPassword);
        TestUtils.setPrivateField(config, "scriptFetchEnabled", origFetchEnabled);
        TestUtils.setPrivateField(config, "scriptFetchAllowlist", origAllowlist);
        TestUtils.setPrivateField(config, "maxMemory", origMaxMemory);
        TestUtils.setPrivateField(config, "scriptsEnabled", origScriptsEnabled);
    }

    @Test
    public void test_the_shipped_admin_password_is_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", Globals.DEFAULT_ADMIN_PASSWORD);
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultAdminPassword();
            assertLogged(logWriter, "defaultAdminPassword is still set to the well-known default");
        }
    }

    @Test
    public void test_a_changed_admin_password_is_not_warned_about() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "not-the-default");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultAdminPassword();
            logWriter.verifyNoInteractions();
        }
    }

    // '*' is the shipped default, so a node with fetch turned on reaches anything routable from it -
    // the instance-metadata endpoint included. That is the warning an operator most needs to see.
    @Test
    public void test_a_wildcard_fetch_allowlist_is_warned_about() throws Exception {
        enableFetch("*");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfScriptFetchEnabled();
            assertLogged(logWriter, "169.254.169.254");
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

    // maxMemory and the metadata caps are budgeted separately, so an operator sizing -Xmx from
    // maxMemory alone undercounts; the warning names the sum.
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

    private void enableFetch(String allowlist) throws Exception {
        TestUtils.setPrivateField(config, "scriptFetchEnabled", "true");
        TestUtils.setPrivateField(config, "scriptFetchAllowlist", allowlist);
    }

    private static void assertLogged(MockedStatic<LogWriter> logWriter, String expected) {
        logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains(expected))));
    }

    @Test
    public void test_every_warning_is_silent_on_a_sane_configuration() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "not-the-default");
        TestUtils.setPrivateField(config, "scriptFetchEnabled", "false");
        assertTrue(config.getMaxMemoryBytes() > 0, "the fixture must have a bounded cache");
        try (var logWriter = mockStatic(LogWriter.class)) {
            StartupWarnings.warnIfDefaultAdminPassword();
            StartupWarnings.warnIfScriptFetchEnabled();
            logWriter.verifyNoInteractions();
        }
    }
}
