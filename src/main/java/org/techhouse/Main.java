package org.techhouse;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import javax.net.ssl.SSLServerSocketFactory;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.SocketServer;
import org.techhouse.conn.tls.TlsContextFactory;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.data.auth.GlobalPermissionType;
import org.techhouse.data.auth.PasswordHasher;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.LogWriter;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TransactionOperationHelper;

public class Main {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
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

    public static void main(String[] args) throws IOException {
        LogWriter.createLogPathAndRemoveOldFiles();
        fs.createBaseDbPath();
        fs.createAdminDatabase();
        cache.loadAdminData();
        cleanupOrphanedTransactions();
        bootstrapDefaultAdmin();
        final var port = getPort(args);
        backgroundTaskManager.startBackgroundWorkers();
        listenManager.startWorkers();
        memoryManagement.loadProfileFromAdmin();
        memoryManagement.startSweepThread();
        warnIfXmxExceedsMaxMemory();
        warnIfDefaultAdminPassword();
        startClusterIfEnabled();
        // Built eagerly so a self-signed keystore is generated (and its security warning logged) at startup,
        // not lazily on the first client connection.
        final var sslServerSocketFactory = createTlsFactory();
        final var server = new SocketServer(port, sslServerSocketFactory);
        server.serve();
    }

    private static void startClusterIfEnabled() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        try {
            final var factory = clusterConfig.tlsEnabled() ? TlsContextFactory.createServerSocketFactory(config) : null;
            final var clusterServer = new ClusterServer(clusterConfig.clusterPort(), clusterConfig.bindAddress(),
                    factory);
            clusterServer.start();
            membershipService.addListener(ownershipManager);
            membershipService.addListener(antiEntropyService);
            membershipService.start();
            ownershipManager.setSelfNodeId(membershipService.getSelf().getNodeId());
            antiEntropyService.start();
        } catch (IOException e) {
            logger.fatal("Failed to start the cluster server", e);
            throw new RuntimeException("Failed to start the cluster server", e);
        }
    }

    // A transaction only lives while its connection is open, so any operation records still in
    // admin/transactions at startup were orphaned by a crash/restart and are discarded (never applied).
    private static void cleanupOrphanedTransactions() {
        try {
            TransactionOperationHelper.cleanupOrphansAtStartup();
        } catch (Exception e) {
            logger.error("Failed to clean up orphaned transactions at startup", e);
        }
    }

    static SSLServerSocketFactory createTlsFactory() {
        if (!config.isTlsEnabled()) {
            return null;
        }
        return TlsContextFactory.createServerSocketFactory(config);
    }

    static void warnIfDefaultAdminPassword() {
        if (Globals.DEFAULT_ADMIN_PASSWORD.equals(config.getDefaultAdminPassword())) {
            logger.warning("SECURITY WARNING: defaultAdminPassword is still set to the well-known default value. "
                    + "Change it in lwnrdb.cfg and update the admin user's password immediately to avoid "
                    + "unauthorized access.");
        }
    }

    static void warnIfXmxExceedsMaxMemory() {
        if (config.isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        final var xmx = Runtime.getRuntime().maxMemory();
        final var cap = config.getMaxMemoryBytes();
        if (xmx > cap * 2L) {
            logger.warning("JVM -Xmx (" + xmx + " bytes) is more than 2x the configured maxMemory (" + cap
                    + " bytes). The cap drives in-memory eviction but cannot constrain heap the JVM keeps "
                    + "committed; set -Xmx close to maxMemory so the OS-visible process size "
                    + "matches the configured budget.");
        }
    }

    private static void bootstrapDefaultAdmin() throws IOException {
        final var existingAdmins = cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count();

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

        final var adminUser = new AdminUserEntry(defaultUsername, passwordHash, true, globalPerms, new HashMap<>(),
                new HashMap<>());

        try {
            AdminOperationHelper.saveUserEntry(adminUser);
            logger.info("Bootstrapped default admin user: " + defaultUsername);
        } catch (InterruptedException e) {
            throw new RuntimeException("Failed to bootstrap admin user", e);
        }
    }
}
