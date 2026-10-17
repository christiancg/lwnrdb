package org.techhouse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.Logger;

public final class AdminPasswordGuard {
    static final String SHIPPED_PASSWORD = "administrator";
    static final Set<String> COMMON_PASSWORDS = Set.of("password", "password1", "password12", "password123", "passw0rd",
            "p@ssw0rd", "changeme", "change_it", "changeit", "12345678", "123456789", "1234567890", "87654321",
            "11111111", "00000000", "qwertyui", "qwertyuiop", "qwerty123", "letmein1", "welcome1", "iloveyou",
            "sunshine", "superman", "baseball", "football", "trustno1", "admin123", "admin1234", "adminadmin",
            "administrator1", "rootroot", "lwnrdb123");

    private static final Configuration config = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(AdminPasswordGuard.class);

    private AdminPasswordGuard() {
    }

    public static void refuseWeakPassword() {
        final var password = config.getDefaultAdminPassword();
        if (SHIPPED_PASSWORD.equalsIgnoreCase(password)) {
            refuse("defaultAdminPassword is the well-known shipped value '" + SHIPPED_PASSWORD
                    + "'. Set a different password in lwnrdb.cfg, or remove the key if an admin user already exists.");
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            refuse("defaultAdminPassword is one of the most commonly used passwords. Set a stronger one in"
                    + " lwnrdb.cfg, or remove the key if an admin user already exists.");
        }
        if (password.equalsIgnoreCase(config.getDefaultAdminUsername())) {
            refuse("defaultAdminPassword is the same as defaultAdminUsername. Set a different password in"
                    + " lwnrdb.cfg, or remove the key if an admin user already exists.");
        }
    }

    public static void refuseUnsetPasswordWhenBootstrapping(Cache cache) {
        final var adminExists = cache.getAllAdminUserEntries().stream().anyMatch(AdminUserEntry::isAdmin);
        if (!adminExists && config.getDefaultAdminPassword().isBlank()) {
            refuse("no admin user exists and defaultAdminPassword is not set. Set defaultAdminPassword in lwnrdb.cfg"
                    + " (at least " + Globals.PASSWORD_MIN_LENGTH + " characters) to create the first admin.");
        }
    }

    private static void refuse(String reason) {
        logger.fatal("Insecure configuration, the application will not start: " + reason);
        throw new InvalidConfigurationException(List.of(reason));
    }
}
