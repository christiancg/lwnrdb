package org.techhouse.test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The full shipped configuration as a raw key/value map, shared by the parsing and validation tests.
 * It is spelled out literally rather than derived from {@code ConfigKey}: these tests exist to pin
 * the shipped value of every key, so reading the expectations out of the registry under test would
 * make them tautological.
 */
public final class ConfigFixture {

    private ConfigFixture() {
    }

    public static Map<String, String> fullValid() {
        final var map = new HashMap<String, String>();
        map.put("port", "8989");
        map.put("maxConnections", "100");
        map.put("filePath", "db");
        map.put("backgroundProcessingThreads", "10");
        map.put("logPath", "logs");
        map.put("maxLogFiles", "7");
        map.put("maxPageSize", "2Mb");
        map.put("maxEntrySize", "1Mb");
        map.put("defaultAdminUsername", "admin");
        map.put("defaultAdminPassword", "administrator");
        map.put("maxMemory", "512Mb");
        map.put("transactionLockTimeoutMs", "5000");
        map.put("tlsEnabled", "false");
        map.put("clusterEnabled", "false");
        map.put("clusterPort", "9990");
        map.put("clusterBindAddress", "0.0.0.0");
        map.put("clusterAdvertisedAddress", "127.0.0.1");
        map.put("clusterSeeds", "");
        map.put("nodeId", "");
        map.put("clusterExpectedSize", "1");
        map.put("gossipIntervalMs", "1000");
        map.put("suspectTimeoutMs", "5000");
        map.put("deadTimeoutMs", "15000");
        map.put("replicationAckTimeoutMs", "5000");
        map.put("virtualNodesPerNode", "128");
        map.put("readFallbackToLocal", "true");
        map.put("scriptRoutingEnabled", "true");
        map.put("scriptLocalityWeight", "50");
        map.put("clusterTlsEnabled", "false");
        map.put("clusterSecret", "");
        map.put("antiEntropyIntervalMs", "60000");
        map.put("tombstoneRetentionMs", "86400000");
        map.put("scriptTimeZone", "UTC");
        map.put("scriptLocale", "en-US");
        map.put("scriptsEnabled", "false");
        map.put("scriptInstructionBudget", "10000000");
        map.put("scriptTimeoutMs", "5000");
        map.put("scriptMaxDepth", "200");
        map.put("scriptMaxSourceBytes", "256Kb");
        map.put("scriptMaxLogLines", "1000");
        map.put("scriptMaxLogLineChars", "4096");
        map.put("scriptMaxMemoryBytes", "64Mb");
        map.put("scriptMaxResultBytes", "16Mb");
        map.put("scriptCursorBatchSize", "500");
        map.put("scriptCursorMaxBatchSize", "5000");
        map.put("aggregationScriptInstructionBudget", "1000000");
        map.put("aggregationScriptTimeoutMs", "2000");
        map.put("aggregationScriptMaxSourceBytes", "16Kb");
        map.put("maxConcurrentScripts", "16");
        map.put("scriptQueueWaitMs", "250");
        map.put("procedureCacheSize", "128");
        map.put("triggersEnabled", "false");
        map.put("triggerThreads", "2");
        map.put("triggerQueueSize", "10000");
        map.put("triggerMaxDepth", "3");
        map.put("triggerTimeoutMs", "1000");
        map.put("shutdownTimeoutMs", "15000");
        map.put("triggerRunLogEnabled", "true");
        map.put("triggerRunRetentionMs", "86400000");
        map.put("beforeHookInstructionBudget", "200000");
        map.put("beforeHookTimeoutMs", "200");
        map.put("schedulesEnabled", "false");
        map.put("scheduleThreads", "2");
        map.put("scheduleQueueSize", "100");
        map.put("scheduleTickMs", "1000");
        map.put("scheduleRefreshMs", "60000");
        map.put("scheduleTimeoutMs", "30000");
        map.put("scheduleMaxPerDatabase", "100");
        map.put("scriptTextImportEnabled", "false");
        map.put("scriptProcedureImportEnabled", "true");
        return map;
    }

    // Validation actually reads and writes the configured paths, so both point at a writable temp dir.
    public static Map<String, String> fullValid(Path writablePath) {
        final var map = fullValid();
        map.put("filePath", writablePath.toString());
        map.put("logPath", writablePath.toString());
        return map;
    }

    // A three-node cluster with seeds and a shared secret: what the cluster cross-key rules need to
    // have anything to check.
    public static Map<String, String> clusterEnabled(Path writablePath) {
        final var map = fullValid(writablePath);
        map.put("clusterEnabled", "true");
        map.put("clusterExpectedSize", "3");
        map.put("clusterSeeds", "host1:9990,host2:9991");
        map.put("clusterSecret", "shared-secret");
        return map;
    }
}
