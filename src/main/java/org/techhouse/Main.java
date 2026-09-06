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
import org.techhouse.ops.ScheduleDispatcher;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.simplejs.host.HostAllowlist;

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
        // Its own pool, not the background queue: see TriggerExecutor.
        triggerExecutor.start(TriggerDispatcher::dispatch);
        // After cleanupOrphanedTransactions above, which finishes any run whose commit was in flight and so
        // removes its record; whatever records remain are runs that genuinely never applied.
        TriggerRunRecovery.garbageCollect();
        TriggerRunRecovery.warnAboutStrandedRuns();
        TriggerRunRecovery.recoverLocal();
        startSchedulerIfEnabled();
        listenManager.startWorkers();
        memoryManagement.loadProfileFromAdmin();
        memoryManagement.startSweepThread();
        scriptRunHistory.startSweep();
        warnIfXmxExceedsMaxMemory();
        warnIfCachesExceedHeap();
        warnIfDefaultAdminPassword();
        warnIfScriptFetchEnabled();
        startClusterIfEnabled();
        // Built eagerly so a self-signed keystore is generated (and its security warning logged) at startup,
        // not lazily on the first client connection.
        final var sslServerSocketFactory = createTlsFactory();
        final var server = new SocketServer(port, sslServerSocketFactory);
        registerShutdownHook(server);
        server.serve();
    }

    // The registry walks the filesystem, so it is only built when the feature is on: a node with schedules
    // disabled must not pay a per-database directory listing at startup.
    private static void startSchedulerIfEnabled() {
        if (!config.isSchedulesEnabled()) {
            return;
        }
        scheduleRegistry.loadAll();
        scheduleExecutor.start(ScheduleDispatcher::dispatch);
    }

    // Registered before serve() blocks, so a SIGTERM (a container stop, a systemctl stop, a rolling restart)
    // runs the ordered shutdown instead of killing the JVM with queues still full.
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
            // Right after the ownership manager: listeners fire in registration order, so the ring this
            // reads is already the rebuilt one.
            membershipService.addListener(metadataCachePruner);
            // Register the admin listener before the document one so a rejoining node conforms its structure
            // (databases/collections/indexes/users) before the document reconciliation repopulates them.
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

    // Outbound HTTP from stored code is a capability worth naming at startup rather than leaving in a
    // config file: an operator reading the log should be able to see what this node may reach.
    static void warnIfScriptFetchEnabled() {
        if (!config.isScriptFetchEnabled()) {
            return;
        }
        final var allowlist = config.getScriptFetchAllowlist();
        if (HostAllowlist.allowsEverything(allowlist)) {
            logger.warning("SECURITY WARNING: scriptFetchAllowlist is '*', so any script may make this server "
                    + "issue HTTP requests to any host it can reach - including services inside your network "
                    + "and the cloud instance-metadata endpoint (169.254.169.254), not just the public "
                    + "internet. Narrow it in lwnrdb.cfg to the hosts your scripts actually call, or set "
                    + "scriptFetchEnabled=false to remove the capability.");
        } else if (allowlist.isEmpty()) {
            logger.warning("scriptFetchEnabled is true but scriptFetchAllowlist is empty, so every fetch will be "
                    + "refused. Name the hosts scripts may reach.");
        } else {
            logger.info("Script fetch is enabled for: " + String.join(", ", allowlist));
        }
    }

    // The metadata caps are budgeted separately from maxMemory, so the heap a fully-warm node needs is the
    // sum of the two. Warned about together because an operator sizing -Xmx from maxMemory alone undercounts.
    static void warnIfCachesExceedHeap() {
        final var xmx = Runtime.getRuntime().maxMemory();
        final var metadataCap = config.getProcedureCacheMaxBytes() + config.getSchemaCacheMaxBytes()
                + config.getScheduleCacheMaxBytes();
        final var userCap = config.isCachingDisabled() || config.isCacheUnlimited() ? 0L : config.getMaxMemoryBytes();
        final var scriptCap = scriptBudgetBytes();
        final var total = userCap + metadataCap + scriptCap;
        if (total > xmx) {
            logger.warning("The configured memory budgets total " + total + " bytes (maxMemory " + userCap
                    + " + procedureCacheMaxBytes/schemaCacheMaxBytes/scheduleCacheMaxBytes " + metadataCap
                    + " + concurrent script budgets " + scriptCap + ") but JVM -Xmx is only " + xmx
                    + " bytes. Lower the budgets or raise -Xmx, otherwise a fully-warm node cannot fit in heap.");
        }
    }

    // The concurrent interpreters this node can hold at once: client runs are capped by
    // maxConcurrentScripts, triggers and schedules by their own worker pools. Each may allocate up to
    // scriptMaxMemoryBytes, and that is additive with the cache budgets.
    private static long scriptBudgetBytes() {
        if (!config.isScriptsEnabled()) {
            return 0L;
        }
        final var interpreters = (long) config.getMaxConcurrentScripts() + config.getTriggerThreads()
                + config.getScheduleThreads();
        return interpreters * config.getScriptMaxMemoryBytes();
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
