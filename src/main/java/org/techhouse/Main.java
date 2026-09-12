package org.techhouse;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import javax.net.ssl.SSLServerSocketFactory;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.ScheduleExecutor;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cache.Cache;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.MetadataCachePruner;
import org.techhouse.cluster.TransactionSessionReaper;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
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
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunRecovery;

public class Main {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private static final BackgroundTaskManager backgroundTaskManager = IocContainer.get(BackgroundTaskManager.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private static final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private static final ScheduleExecutor scheduleExecutor = IocContainer.get(ScheduleExecutor.class);
    private static final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final OwnershipManager ownershipManager = IocContainer.get(OwnershipManager.class);
    private static final MetadataCachePruner metadataCachePruner = IocContainer.get(MetadataCachePruner.class);
    private static final ShutdownCoordinator shutdownCoordinator = IocContainer.get(ShutdownCoordinator.class);
    private static ClusterServer clusterServer;
    private static final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private static final AdminAntiEntropyService adminAntiEntropyService = IocContainer
            .get(AdminAntiEntropyService.class);
    private static final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private static final TransactionSessionReaper transactionSessionReaper = IocContainer
            .get(TransactionSessionReaper.class);
    private static final Tx2pcRecovery tx2pcRecovery = IocContainer.get(Tx2pcRecovery.class);
    private static final ScriptRunHistory scriptRunHistory = IocContainer.get(ScriptRunHistory.class);
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
        triggerExecutor.start(TriggerDispatcher::dispatch);
        // Must run after cleanupOrphanedTransactions: the records left are runs that never applied.
        TriggerRunRecovery.garbageCollect();
        TriggerRunRecovery.warnAboutStrandedRuns();
        TriggerRunRecovery.recoverLocal();
        startSchedulerIfEnabled();
        listenManager.startWorkers();
        memoryManagement.loadProfileFromAdmin();
        memoryManagement.startSweepThread();
        scriptRunHistory.startSweep();
        StartupWarnings.warnIfXmxExceedsMaxMemory();
        StartupWarnings.warnIfCachesExceedHeap();
        StartupWarnings.warnIfDefaultAdminPassword();
        StartupWarnings.warnIfScriptFetchEnabled();
        startClusterIfEnabled();
        final var sslServerSocketFactory = createTlsFactory();
        final var server = new SocketServer(port, sslServerSocketFactory);
        registerShutdownHook(server);
        server.serve();
    }

    private static void startSchedulerIfEnabled() {
        if (!config.isSchedulesEnabled()) {
            return;
        }
        scheduleRegistry.loadAll();
        scheduleExecutor.start(ScheduleDispatcher::dispatch);
    }

    private static void registerShutdownHook(SocketServer server) {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> shutdownCoordinator.shutdown(server, clusterServer), "shutdown"));
    }

    private static void startClusterIfEnabled() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        try {
            final var factory = clusterConfig.tlsEnabled() ? TlsContextFactory.createServerSocketFactory(config) : null;
            clusterServer = new ClusterServer(clusterConfig.clusterPort(), clusterConfig.bindAddress(), factory);
            clusterServer.start();
            adminEpoch.load();
            membershipService.addListener(ownershipManager);
            // Listeners fire in registration order: this must follow the ownership manager to read the rebuilt ring.
            membershipService.addListener(metadataCachePruner);
            // Admin listener before the document one: structure must conform before documents repopulate it.
            membershipService.addListener(adminAntiEntropyService);
            membershipService.addListener(antiEntropyService);
            membershipService.addListener(transactionSessionReaper);
            membershipService.addListener(tx2pcRecovery);
            membershipService.start();
            ownershipManager.setSelfNodeId(membershipService.getSelf().getNodeId());
            adminAntiEntropyService.start();
            antiEntropyService.start();
            tx2pcRecovery.recover();
            tx2pcRecovery.start();
        } catch (IOException e) {
            logger.fatal("Failed to start the cluster server", e);
            throw new RuntimeException("Failed to start the cluster server", e);
        }
    }

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

    private static void bootstrapDefaultAdmin() throws IOException {
        final var existingAdmins = cache.getAllAdminUserEntries().stream().filter(AdminUserEntry::isAdmin).count();

        if (existingAdmins > 0) {
            return;
        }

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
