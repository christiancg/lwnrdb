package org.techhouse;

import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.Logger;

public final class AdminPasswordGuard {
    static final String SHIPPED_PASSWORD = "administrator";

    private static final Configuration config = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(AdminPasswordGuard.class);

    private AdminPasswordGuard() {
    }

    public static void refuseShippedPassword() {
        if (SHIPPED_PASSWORD.equals(config.getDefaultAdminPassword())) {
            refuse("defaultAdminPassword is the well-known shipped value '" + SHIPPED_PASSWORD
                    + "'. Set a different password in lwnrdb.cfg, or remove the key if an admin user already exists.");
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
