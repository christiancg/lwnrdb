package org.techhouse;

import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;
import org.techhouse.simplejs.host.HostAllowlist;

public final class StartupWarnings {
    private static final Configuration config = Configuration.getInstance();
    private static final Logger logger = Logger.logFor(StartupWarnings.class);

    private StartupWarnings() {
    }

    public static void warnIfDefaultAdminPassword() {
        if (Globals.DEFAULT_ADMIN_PASSWORD.equals(config.getDefaultAdminPassword())) {
            logger.warning("SECURITY WARNING: defaultAdminPassword is still set to the well-known default value. "
                    + "Change it in lwnrdb.cfg and update the admin user's password immediately to avoid "
                    + "unauthorized access.");
        }
    }

    public static void warnIfScriptFetchEnabled() {
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

    public static void warnIfXmxExceedsMaxMemory() {
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
}
