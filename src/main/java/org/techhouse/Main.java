package org.techhouse;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.SocketServer;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.LogWriter;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;

import java.util.HashSet;
import java.util.HashMap;

import java.io.IOException;

public class Main {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final Logger logger = Logger.logFor(Main.class);

    private static int getPort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (Exception e) {
                throw new InvalidPortException(args[0], e);
            }
        } else {
            return config.getPort();
        }
    }

    public static void main(String[] args)
            throws IOException {
        LogWriter.createLogPathAndRemoveOldFiles();
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        cache.loadAdminData();
        bootstrapDefaultAdmin();
        final var port = getPort(args);
        final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
        backgroundTaskManager.startBackgroundWorkers();
        memoryManagement.loadProfileFromAdmin();
        memoryManagement.startSweepThread();
        warnIfXmxExceedsMaxMemory();
        warnIfDefaultAdminPassword();
        final var server = new SocketServer(port);
        server.serve();
    }

    static void warnIfDefaultAdminPassword() {
        if (Globals.DEFAULT_ADMIN_PASSWORD.equals(config.getDefaultAdminPassword())) {
            logger.warning(
                    "SECURITY WARNING: defaultAdminPassword is still set to the well-known default value. " +
                    "Change it in lwnrdb.cfg and update the admin user's password immediately to avoid " +
                    "unauthorized access.");
        }
    }

    static void warnIfXmxExceedsMaxMemory() {
        if (config.isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        final var xmx = Runtime.getRuntime().maxMemory();
        final var cap = config.getMaxMemoryBytes();
        if (xmx > cap * 2L) {
            logger.warning(
                    "JVM -Xmx (" + xmx + " bytes) is more than 2x the configured maxMemory (" + cap +
                    " bytes). The cap drives in-memory eviction but cannot constrain heap the JVM keeps " +
                    "committed; set -Xmx close to maxMemory so the OS-visible process size " +
                    "matches the configured budget.");
        }
    }

    private static void bootstrapDefaultAdmin() throws IOException {
        final var existingAdmins = cache.getAllAdminUserEntries().stream()
                .filter(AdminUserEntry::isAdmin)
                .count();

        if (existingAdmins > 0) {
            return;
        }

        // defaultAdminUsername/defaultAdminPassword are validated at startup
        // (non-blank username, password at least Globals.PASSWORD_MIN_LENGTH chars),
        // so they are guaranteed to be usable here.
        final var defaultUsername = config.getDefaultAdminUsername();
        final var defaultPassword = config.getDefaultAdminPassword();

        final var passwordHash = PasswordHasher.hash(defaultPassword);
        final var globalPerms = new HashSet<GlobalPermissionType>();
        globalPerms.add(GlobalPermissionType.CREATE_DATABASE);
        globalPerms.add(GlobalPermissionType.DROP_DATABASE);

        final var adminUser = new AdminUserEntry(
                defaultUsername,
                passwordHash,
                true,
                globalPerms,
                new HashMap<>(),
                new HashMap<>()
        );

        try {
            AdminOperationHelper.saveUserEntry(adminUser);
            logger.info("Bootstrapped default admin user: " + defaultUsername);
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to bootstrap admin user", e);
        }
    }
}
