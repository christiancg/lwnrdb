package org.techhouse;

import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
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
        warnIfXmxExceedsCacheCap();
        final var server = new SocketServer(port);
        server.serve();
    }

    static void warnIfXmxExceedsCacheCap() {
        if (config.isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        final var xmx = Runtime.getRuntime().maxMemory();
        final var cap = config.getMaxCollectionCacheBytes();
        if (xmx > cap * 2L) {
            Logger.logFor(Main.class).warning(
                    "JVM -Xmx (" + xmx + " bytes) is more than 2x the configured maxCollectionCache (" + cap +
                    " bytes). The cap drives in-memory eviction but cannot constrain heap the JVM keeps " +
                    "committed; set -Xmx close to maxCollectionCache so the OS-visible process size " +
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

        final var defaultUsername = config.getDefaultAdminUsername();
        final var defaultPassword = config.getDefaultAdminPassword();

        if (defaultUsername == null || defaultUsername.isBlank() ||
            defaultPassword == null || defaultPassword.isBlank()) {
            Logger.logFor(Main.class).warning(
                    "No admin user found and no default admin configured. " +
                    "Set defaultAdminUsername and defaultAdminPassword in lwnrdb.cfg to bootstrap.");
            return;
        }

        if (defaultPassword.length() < 8) {
            Logger.logFor(Main.class).warning(
                    "Default admin password must be at least 8 characters. Not bootstrapping.");
            return;
        }

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
            Logger.logFor(Main.class).info("Bootstrapped default admin user: " + defaultUsername);
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to bootstrap admin user", e);
        }
    }
}
