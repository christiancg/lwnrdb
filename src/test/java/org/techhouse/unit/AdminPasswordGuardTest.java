package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.AdminPasswordGuard;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminPasswordGuardTest {
    private final Configuration config = Configuration.getInstance();
    private String origPassword;

    @BeforeEach
    public void setUp() {
        origPassword = config.getDefaultAdminPassword();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", origPassword);
    }

    private static Cache cacheWith(Boolean... admins) {
        final var cache = mock(Cache.class);
        final var users = new ArrayList<AdminUserEntry>();
        for (final var admin : admins) {
            final var user = mock(AdminUserEntry.class);
            when(user.isAdmin()).thenReturn(admin);
            users.add(user);
        }
        when(cache.getAllAdminUserEntries()).thenReturn(List.copyOf(users));
        return cache;
    }

    @Test
    public void test_shipped_password_is_refused_even_when_an_admin_exists() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "administrator");
        try (var logWriter = mockStatic(LogWriter.class)) {
            final var refusal = assertThrows(InvalidConfigurationException.class,
                    AdminPasswordGuard::refuseShippedPassword);
            assertTrue(refusal.getMessage().contains("defaultAdminPassword"));
            logWriter.verify(() -> LogWriter
                    .writeLogEntry(argThat(entry -> entry.contains("FATAL") && entry.contains("will not start"))));
        }
        assertDoesNotThrow(() -> AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cacheWith(true)));
    }

    @Test
    public void test_a_custom_password_passes_the_shipped_check() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", TestGlobals.ADMIN_PASSWORD);
        assertDoesNotThrow(AdminPasswordGuard::refuseShippedPassword);
    }

    @Test
    public void test_shipped_password_comparison_is_exact() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "Administrator");
        assertDoesNotThrow(AdminPasswordGuard::refuseShippedPassword);
    }

    @Test
    public void test_a_blank_password_passes_the_shipped_check() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "");
        assertDoesNotThrow(AdminPasswordGuard::refuseShippedPassword);
    }

    @Test
    public void test_blank_password_is_refused_when_no_admin_exists() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "");
        try (var logWriter = mockStatic(LogWriter.class)) {
            final var refusal = assertThrows(InvalidConfigurationException.class,
                    () -> AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cacheWith()));
            assertTrue(refusal.getMessage().contains("no admin user exists"));
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("FATAL"))));
        }
    }

    @Test
    public void test_blank_password_is_accepted_when_an_admin_exists() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "");
        assertDoesNotThrow(() -> AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cacheWith(false, true)));
    }

    @Test
    public void test_non_admin_users_do_not_count_as_an_admin() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", "   ");
        try (var _ = mockStatic(LogWriter.class)) {
            assertThrows(InvalidConfigurationException.class,
                    () -> AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cacheWith(false)));
        }
    }

    @Test
    public void test_a_set_password_passes_when_bootstrapping() throws Exception {
        TestUtils.setPrivateField(config, "defaultAdminPassword", TestGlobals.ADMIN_PASSWORD);
        assertDoesNotThrow(() -> AdminPasswordGuard.refuseUnsetPasswordWhenBootstrapping(cacheWith()));
    }
}
