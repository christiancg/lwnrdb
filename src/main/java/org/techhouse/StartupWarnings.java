package org.techhouse;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.OnDiskNameRegistry;
import org.techhouse.simplejs.host.HostAllowlist;
import org.techhouse.simplejs.host.PublicAddressPolicy;
import org.techhouse.utils.OwnerOnlyPermissions;

public final class StartupWarnings {
    private static final Configuration config = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(StartupWarnings.class);

    private StartupWarnings() {
    }

    public static void retainAndReportUncleanStops() {
        final var dirty = IocContainer.get(FileSystem.class).indexMarkers().retainUncleanStops();
        if (!dirty.isEmpty()) {
            logger.warning("These collections stopped with field-index work outstanding, so their indexes may be"
                    + " missing entries: " + String.join(", ", dirty) + ". Run REINDEX on each of them.");
        }
        final var unbuilt = IocContainer.get(FileSystem.class).indexMarkers().listMarked();
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
        if (config.isTlsEnabled()
                && ConfigKey.TLS_KEYSTORE_PASSWORD.defaultValue().equals(config.getTlsKeystorePassword())) {
            logger.warning("SECURITY WARNING: tlsKeystorePassword is still the shipped value '"
                    + ConfigKey.TLS_KEYSTORE_PASSWORD.defaultValue() + "', so anyone who can read the keystore file "
                    + "can extract the private key. Set your own in lwnrdb.cfg.");
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
        if (OwnerOnlyPermissions.isAccessibleByOthers(path)) {
            logger.warning("SECURITY WARNING: the " + what + " at " + path.toAbsolutePath()
                    + " is accessible by group or other users, and it holds data, credentials or secrets. Run '" + fix
                    + " " + path.toAbsolutePath() + "' to restrict it to the server's own user.");
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
