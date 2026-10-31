package org.techhouse;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.cache.Cache;
import org.techhouse.cache.CacheSizeEstimator;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.SizeParser;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.io.OwnerOnlyFiles;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.simplejs.host.HostAllowlist;
import org.techhouse.simplejs.host.PublicAddressPolicy;

public final class StartupWarnings {
    private static final Configuration config = Configuration.getInstance();
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Logger logger = Logger.logFor(StartupWarnings.class);
    private static final List<ConfigKey> LIMITS_OFF_AT_ZERO = List.of(ConfigKey.MAX_CONNECTIONS,
            ConfigKey.MAX_UNAUTHENTICATED_CONNECTIONS_PER_ADDRESS, ConfigKey.MAX_UNAUTHENTICATED_INVALID_REQUESTS,
            ConfigKey.MAX_FAILED_AUTH_ATTEMPTS, ConfigKey.AUTH_FAILURE_DELAY_BASE_MS, ConfigKey.TRANSACTION_MAX_OPS,
            ConfigKey.TRANSACTION_MAX_AGE_MS, ConfigKey.MAX_LISTENS_PER_CONNECTION, ConfigKey.MAX_LISTENS,
            ConfigKey.MAX_LISTENS_PER_USER, ConfigKey.MAX_FILTER_OPERANDS, ConfigKey.AGGREGATE_TIMEOUT_MS,
            ConfigKey.MAX_CONCURRENT_SCRIPTS, ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_USER,
            ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_DATABASE);
    private static final List<ConfigKey> SIZE_LIMITS_OFF_AT_ZERO = List.of(ConfigKey.MAX_MEMORY);

    private StartupWarnings() {
    }

    public static void retainAndReportUncleanStops() {
        final var dirty = fs.indexMarkers().retainUncleanStops();
        if (!dirty.isEmpty()) {
            logger.warning("These collections stopped with field-index work outstanding, so their indexes may be"
                    + " missing entries: " + String.join(", ", dirty) + ". Run REINDEX on each of them.");
        }
        final var unbuilt = fs.indexMarkers().listMarked();
        if (!unbuilt.isEmpty()) {
            logger.warning("These field indexes were left half-built or half-dropped, so they are answered by a"
                    + " full scan until rebuilt: " + String.join(", ", unbuilt)
                    + ". Run REINDEX (or DROP_INDEX) on each of them.");
        }
    }

    public static void warnIfPlaintextClients() {
        if (!config.isTlsEnabled()) {
            logger.warning("SECURITY WARNING: tlsEnabled=false, so client connections are plaintext: passwords, "
                    + "session data and documents cross the network unencrypted. Set tlsEnabled=true and point "
                    + "tlsKeystorePath at a CA-issued PKCS12 keystore before exposing this server beyond localhost.");
        }
    }

    public static void warnIfDefaultKeystorePassword() {
        if (usesKeystore() && ConfigKey.TLS_KEYSTORE_PASSWORD.defaultValue().equals(config.getTlsKeystorePassword())) {
            logger.warning("SECURITY WARNING: tlsKeystorePassword is still the shipped value '"
                    + ConfigKey.TLS_KEYSTORE_PASSWORD.defaultValue() + "', so anyone who can read the keystore file "
                    + "can extract the private key. Set your own in lwnrdb.cfg.");
        }
    }

    public static void warnIfProtectiveLimitsAreOff() {
        final var off = new ArrayList<String>();
        for (final var key : LIMITS_OFF_AT_ZERO) {
            if (Long.parseLong(config.stringValue(key).trim()) == 0L) {
                off.add(key.key());
            }
        }
        for (final var key : SIZE_LIMITS_OFF_AT_ZERO) {
            if (SizeParser.parse(config.stringValue(key)) == 0L) {
                off.add(key.key());
            }
        }
        if (!off.isEmpty()) {
            logger.warning("SECURITY WARNING: these limits are set to 0, which turns them off: "
                    + String.join(", ", off)
                    + ". Without them one client can hold every connection, retry passwords without pause or tie up"
                    + " the server with unbounded work. Set a positive value unless something in front of this"
                    + " server enforces the same limit.");
        }
    }

    public static void warnIfStorageIsReadableByOthers() {
        warnIfAccessibleByOthers(Path.of(config.getFilePath()), "data folder (filePath)", "chmod 700");
        warnIfAccessibleByOthers(Path.of(config.getLogPath()), "log folder (logPath)", "chmod 700");
        if (usesKeystore()) {
            warnIfAccessibleByOthers(Path.of(config.getTlsKeystorePath()), "TLS keystore (tlsKeystorePath)",
                    "chmod 600");
        }
        warnIfAccessibleByOthers(Path.of(Globals.FILE_CONFIG_NAME).toAbsolutePath(), Globals.FILE_CONFIG_NAME,
                "chmod 600");
    }

    private static boolean usesKeystore() {
        return config.isTlsEnabled() || config.isClusterEnabled() && config.isClusterTlsEnabled();
    }

    private static void warnIfAccessibleByOthers(Path path, String what, String fix) {
        if (OwnerOnlyFiles.isAccessibleByOthers(path)) {
            logger.warning("SECURITY WARNING: the " + what + " at " + path.toAbsolutePath()
                    + " is accessible by group or other users, and it holds data, credentials or secrets. Run '" + fix
                    + " " + path.toAbsolutePath() + "' to restrict it to the server's own user.");
        }
    }

    public static void warnIfWritesAreNotSynced() {
        if (!config.isSyncWrites()) {
            logger.warning("DURABILITY WARNING: syncWrites=false, so a write is acknowledged once the operating system"
                    + " has it, and an acknowledged write can be lost on power loss. Set syncWrites=true in"
                    + " lwnrdb.cfg to sync every write to the device before acknowledging it.");
        }
    }

    public static void warnIfFaultInjectionEnabled() {
        if (config.isFaultInjectionEnabled()) {
            logger.warning("SECURITY WARNING: faultInjectionEnabled=true, so any admin can pause or halt this node "
                    + "over the wire. It is for testing and debugging only; turn it off in lwnrdb.cfg.");
        }
    }

    public static void warnIfClusterWithoutTls() {
        if (config.isClusterEnabled() && !config.isClusterTlsEnabled()) {
            logger.warning("SECURITY WARNING: clusterTlsEnabled=false, so the cluster secret and every replicated "
                    + "document cross the network in plaintext and the secret is the only peer authentication. "
                    + "Enable clusterTlsEnabled with a shared keystore.");
        }
    }

    public static void warnIfScriptFetchEnabled() {
        if (!config.isScriptFetchEnabled()) {
            return;
        }
        final var allowlist = config.getScriptFetchAllowlist();
        if (HostAllowlist.allowsEverything(allowlist)) {
            logger.warning("scriptFetchAllowlist is '*', so any script may make this server issue HTTP requests to "
                    + "any PUBLIC host. Loopback, private, link-local (169.254.169.254), CGNAT and unique-local "
                    + "addresses are refused unless the exact host is listed, and listing an internal host exactly "
                    + "re-opens it deliberately. Narrow it in lwnrdb.cfg to the hosts your scripts actually call, "
                    + "or set scriptFetchEnabled=false to remove the capability.");
        } else if (allowlist.isEmpty()) {
            logger.warning("scriptFetchEnabled is true but scriptFetchAllowlist is empty, so every fetch will be "
                    + "refused. Name the hosts scripts may reach.");
        } else {
            logger.info("Script fetch is enabled for: " + String.join(", ", allowlist));
        }
        final var internal = internalEntries(allowlist);
        if (!internal.isEmpty()) {
            logger.warning("SECURITY WARNING: scriptFetchAllowlist names " + String.join(", ", internal)
                    + " exactly, so any script may make this server issue HTTP requests to that internal "
                    + "address. Remove the entry unless scripts must reach it.");
        }
    }

    static List<String> internalEntries(List<String> allowlist) {
        final var internal = new ArrayList<String>();
        for (final var raw : allowlist) {
            final var entry = raw == null ? "" : raw.trim();
            if ("localhost".equalsIgnoreCase(entry) || isPrivateLiteral(entry)) {
                internal.add(entry);
            }
        }
        return internal;
    }

    private static boolean isPrivateLiteral(String entry) {
        final var bare = entry.startsWith("[") && entry.endsWith("]") ? entry.substring(1, entry.length() - 1) : entry;
        try {
            return !PublicAddressPolicy.isPublic(InetAddress.ofLiteral(bare));
        } catch (IllegalArgumentException notLiteral) {
            return false;
        }
    }

    public static void warnIfCachesExceedHeap() {
        final var xmx = Runtime.getRuntime().maxMemory();
        final var metadataCap = config.getMetadataCacheMaxBytes();
        final var userCap = config.isCachingDisabled() || config.isCacheUnlimited() ? 0L : config.getMaxMemoryBytes();
        final var scriptCap = scriptBudgetBytes();
        final var total = userCap + metadataCap + scriptCap;
        if (total > xmx) {
            logger.warning("The configured memory budgets total " + total + " bytes (maxMemory " + userCap
                    + " + metadataCacheMaxBytes " + metadataCap + " + concurrent script budgets " + scriptCap
                    + ") but JVM -Xmx is only " + xmx
                    + " bytes. Lower the budgets or raise -Xmx, otherwise a fully-warm node cannot fit in heap.");
        }
    }

    public static void warnIfCollectionsOutgrowMaxMemory() {
        if (config.isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        final var maxBytes = config.getMaxMemoryBytes();
        final var rowBytes = CacheSizeEstimator.minimumPkEntryBytes();
        for (final var dbName : cache.getUserDatabaseNames()) {
            for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
                final var pkBytes = documentCount(dbName, collName) * rowBytes;
                if (pkBytes > maxBytes) {
                    logger.warning("The primary-key index of " + dbName + "|" + collName + " needs at least " + pkBytes
                            + " bytes of cache, more than maxMemory (" + maxBytes + "), so it is never"
                            + " cached: keyed reads and writes scan pk.idx for the ids they name. Raise maxMemory"
                            + " above " + pkBytes + ".");
                }
            }
        }
    }

    private static long documentCount(String dbName, String collName) {
        final var pages = cache.getAdminPageEntries(dbName, collName);
        var documents = 0L;
        if (pages != null) {
            for (final var page : pages) {
                documents += page.getEntryCount();
            }
        }
        return documents;
    }

    private static long scriptBudgetBytes() {
        if (!config.isScriptsEnabled()) {
            return 0L;
        }
        final var interpreters = (long) config.getMaxConcurrentScripts() + config.getTriggerThreads()
                + config.getScheduleThreads();
        return interpreters * config.getScriptMaxMemoryBytes();
    }

    public static void warnIfNamesShareAnOnDiskKey() {
        for (final var group : OnDiskNameRegistry.groupedByOnDiskKey()) {
            logger.warning("These names share one on-disk path and collide on a case-insensitive filesystem: "
                    + String.join(", ", group)
                    + ". Only one of them has storage; the others read and write that one's files.");
        }
    }

    public static void warnIfDatabaseSharesTheClusterFolder() {
        for (final var dbName : OnDiskNameRegistry.registeredDatabasesInTheClusterFolder()) {
            logger.warning("Database '" + dbName + "' is stored in the folder this node keeps its cluster state in"
                    + " (node id and admin tombstones). Writes to it are refused because the name is reserved; copy"
                    + " its data out with AGGREGATE into another database before enabling clustering.");
        }
    }

    public static void warnIfXmxExceedsMaxMemory() {
        if (config.isCachingDisabled() || config.isCacheUnlimited()) {
            return;
        }
        final var xmx = Runtime.getRuntime().maxMemory();
        final var cap = config.getMaxMemoryBytes();
        if (xmx > cap * 2L) {
            logger.warning("JVM -Xmx (" + xmx + " bytes) is more than 2x the configured maxMemory (" + cap
                    + " bytes). maxMemory bounds the user cache's modelled heap, and the collector, scan pages and "
                    + "requests need the rest; keep maxMemory around half of -Xmx so the heap the JVM commits "
                    + "stays close to what the cache is allowed to hold.");
        }
    }
}
