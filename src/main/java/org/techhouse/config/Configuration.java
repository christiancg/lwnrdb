package org.techhouse.config;

import java.util.Map;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.Logger;

public final class Configuration {
    private static final Configuration config = new Configuration();
    private static final Logger logger = Logger.logFor(Configuration.class);

    private int port;
    private int maxConnections;
    private String filePath;
    private int backgroundProcessingThreads;
    private String logPath;
    private int maxLogFiles;
    private long maxPageSize;
    private long maxEntrySize;
    private String defaultAdminUsername;
    private String defaultAdminPassword;
    private long maxMemoryBytes;
    private long transactionLockTimeoutMs;
    private long shutdownTimeoutMs;
    private boolean tlsEnabled;
    private String tlsKeystorePath;
    private String tlsKeystorePassword;
    private boolean clusterEnabled;
    private int clusterPort;
    private String clusterBindAddress;
    private String clusterAdvertisedAddress;
    private String clusterSeeds;
    private String nodeId;
    private int clusterExpectedSize;
    private long gossipIntervalMs;
    private long suspectTimeoutMs;
    private long deadTimeoutMs;
    private long replicationAckTimeoutMs;
    private int virtualNodesPerNode;
    private boolean readFallbackToLocal;
    private boolean scriptRoutingEnabled;
    private boolean clusterTlsEnabled;
    private String clusterSecret;
    private long antiEntropyIntervalMs;
    private long tombstoneRetentionMs;
    private String scriptTimeZone;
    private String scriptLocale;
    private boolean scriptsEnabled;
    private long scriptInstructionBudget;
    private long scriptTimeoutMs;
    private int scriptMaxDepth;
    private long scriptMaxSourceBytes;
    private int scriptMaxLogLines;
    private int scriptMaxLogLineChars;
    private boolean scriptTextImportEnabled;
    private boolean scriptProcedureImportEnabled;
    private long scriptMaxMemoryBytes;
    private long scriptMaxResultBytes;
    private int scriptCursorBatchSize;
    private int scriptCursorMaxBatchSize;
    private long aggregationScriptInstructionBudget;
    private long aggregationScriptTimeoutMs;
    private long aggregationScriptMaxSourceBytes;
    private int maxConcurrentScripts;
    private long scriptQueueWaitMs;
    private int scriptCompiledCacheSize;
    private int procedureCacheSize;
    private long procedureCacheMaxBytes;
    private long schemaCacheMaxBytes;
    private int triggerCacheMaxEntries;
    private int metadataMissCacheMaxEntries;
    private boolean triggersEnabled;
    private int triggerThreads;
    private int triggerQueueSize;
    private int triggerMaxDepth;
    private long triggerTimeoutMs;
    private boolean triggerRunLogEnabled;
    private long triggerRunRetentionMs;
    private boolean schedulesEnabled;
    private int scheduleThreads;
    private int scheduleQueueSize;
    private long scheduleTickMs;
    private long scheduleRefreshMs;
    private long scheduleTimeoutMs;
    private int scheduleMaxPerDatabase;
    private long scheduleCacheMaxBytes;

    private Configuration() {
    }

    private void load() {
        final var configs = ConfigReader.loadConfiguration();
        final var errors = ConfigurationValidator.validate(configs);
        if (!errors.isEmpty()) {
            logger.fatal("Configuration validation failed, the application will not start:" + Globals.NEWLINE
                    + String.join(Globals.NEWLINE, errors));
            throw new InvalidConfigurationException(errors);
        }
        apply(configs);
    }

    // One unconditional assignment per field, read straight out of the merged map, rather than a
    // switch inside a loop over the entries: a per-key case can only be reached on the iteration that
    // happens to carry that key, so every write reads as a possibly-dead store and the field's value
    // silently depends on iteration order. A key absent from both the bundled defaults and the config
    // file leaves the field at its Java default, exactly as the unmatched-case arm used to.
    private void apply(Map<String, String> configs) {
        port = intOf(configs, "port");
        maxConnections = intOf(configs, "maxConnections");
        filePath = configs.get("filePath");
        backgroundProcessingThreads = intOf(configs, "backgroundProcessingThreads");
        logPath = configs.get("logPath");
        maxLogFiles = intOf(configs, "maxLogFiles");
        maxPageSize = sizeOf(configs, "maxPageSize");
        maxEntrySize = sizeOf(configs, "maxEntrySize");
        defaultAdminUsername = configs.get("defaultAdminUsername");
        defaultAdminPassword = configs.get("defaultAdminPassword");
        maxMemoryBytes = sizeOf(configs, "maxMemory");
        transactionLockTimeoutMs = longOf(configs, "transactionLockTimeoutMs");
        shutdownTimeoutMs = longOf(configs, "shutdownTimeoutMs");
        tlsEnabled = booleanOf(configs, "tlsEnabled");
        tlsKeystorePath = configs.get("tlsKeystorePath");
        tlsKeystorePassword = configs.get("tlsKeystorePassword");
        clusterEnabled = booleanOf(configs, "clusterEnabled");
        clusterPort = intOf(configs, "clusterPort");
        clusterBindAddress = configs.get("clusterBindAddress");
        clusterAdvertisedAddress = configs.get("clusterAdvertisedAddress");
        clusterSeeds = configs.get("clusterSeeds");
        nodeId = configs.get("nodeId");
        clusterExpectedSize = intOf(configs, "clusterExpectedSize");
        gossipIntervalMs = longOf(configs, "gossipIntervalMs");
        suspectTimeoutMs = longOf(configs, "suspectTimeoutMs");
        deadTimeoutMs = longOf(configs, "deadTimeoutMs");
        replicationAckTimeoutMs = longOf(configs, "replicationAckTimeoutMs");
        virtualNodesPerNode = intOf(configs, "virtualNodesPerNode");
        readFallbackToLocal = booleanOf(configs, "readFallbackToLocal");
        scriptRoutingEnabled = booleanOf(configs, "scriptRoutingEnabled");
        clusterTlsEnabled = booleanOf(configs, "clusterTlsEnabled");
        clusterSecret = configs.get("clusterSecret");
        antiEntropyIntervalMs = longOf(configs, "antiEntropyIntervalMs");
        tombstoneRetentionMs = longOf(configs, "tombstoneRetentionMs");
        scriptTimeZone = configs.get("scriptTimeZone");
        scriptLocale = configs.get("scriptLocale");
        scriptsEnabled = booleanOf(configs, "scriptsEnabled");
        scriptInstructionBudget = longOf(configs, "scriptInstructionBudget");
        scriptTimeoutMs = longOf(configs, "scriptTimeoutMs");
        scriptMaxDepth = intOf(configs, "scriptMaxDepth");
        scriptMaxSourceBytes = sizeOf(configs, "scriptMaxSourceBytes");
        scriptMaxLogLines = intOf(configs, "scriptMaxLogLines");
        scriptMaxLogLineChars = intOf(configs, "scriptMaxLogLineChars");
        scriptTextImportEnabled = booleanOf(configs, "scriptTextImportEnabled");
        scriptProcedureImportEnabled = booleanOf(configs, "scriptProcedureImportEnabled");
        scriptMaxMemoryBytes = sizeOf(configs, "scriptMaxMemoryBytes");
        scriptMaxResultBytes = sizeOf(configs, "scriptMaxResultBytes");
        scriptCursorBatchSize = intOf(configs, "scriptCursorBatchSize");
        scriptCursorMaxBatchSize = intOf(configs, "scriptCursorMaxBatchSize");
        aggregationScriptInstructionBudget = longOf(configs, "aggregationScriptInstructionBudget");
        aggregationScriptTimeoutMs = longOf(configs, "aggregationScriptTimeoutMs");
        aggregationScriptMaxSourceBytes = sizeOf(configs, "aggregationScriptMaxSourceBytes");
        maxConcurrentScripts = intOf(configs, "maxConcurrentScripts");
        scriptQueueWaitMs = longOf(configs, "scriptQueueWaitMs");
        scriptCompiledCacheSize = intOf(configs, "scriptCompiledCacheSize");
        procedureCacheSize = intOf(configs, "procedureCacheSize");
        procedureCacheMaxBytes = sizeOf(configs, "procedureCacheMaxBytes");
        schemaCacheMaxBytes = sizeOf(configs, "schemaCacheMaxBytes");
        triggerCacheMaxEntries = intOf(configs, "triggerCacheMaxEntries");
        metadataMissCacheMaxEntries = intOf(configs, "metadataMissCacheMaxEntries");
        triggersEnabled = booleanOf(configs, "triggersEnabled");
        triggerThreads = intOf(configs, "triggerThreads");
        triggerQueueSize = intOf(configs, "triggerQueueSize");
        triggerMaxDepth = intOf(configs, "triggerMaxDepth");
        triggerTimeoutMs = longOf(configs, "triggerTimeoutMs");
        triggerRunLogEnabled = booleanOf(configs, "triggerRunLogEnabled");
        triggerRunRetentionMs = longOf(configs, "triggerRunRetentionMs");
        schedulesEnabled = booleanOf(configs, "schedulesEnabled");
        scheduleThreads = intOf(configs, "scheduleThreads");
        scheduleQueueSize = intOf(configs, "scheduleQueueSize");
        scheduleTickMs = longOf(configs, "scheduleTickMs");
        scheduleRefreshMs = longOf(configs, "scheduleRefreshMs");
        scheduleTimeoutMs = longOf(configs, "scheduleTimeoutMs");
        scheduleMaxPerDatabase = intOf(configs, "scheduleMaxPerDatabase");
        scheduleCacheMaxBytes = sizeOf(configs, "scheduleCacheMaxBytes");
    }

    private static int intOf(Map<String, String> configs, String key) {
        final var value = configs.get(key);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static long longOf(Map<String, String> configs, String key) {
        final var value = configs.get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private static long sizeOf(Map<String, String> configs, String key) {
        final var value = configs.get(key);
        return value == null ? 0L : SizeParser.parse(value);
    }

    private static boolean booleanOf(Map<String, String> configs, String key) {
        return Boolean.parseBoolean(configs.get(key));
    }

    public static Configuration getInstance() {
        if (config.port == 0) {
            config.load();
        }
        return config;
    }

    public int getPort() {
        return port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getBackgroundProcessingThreads() {
        return backgroundProcessingThreads;
    }

    public String getLogPath() {
        return logPath;
    }

    public int getMaxLogFiles() {
        return maxLogFiles;
    }

    public long getMaxPageSize() {
        return maxPageSize;
    }

    public long getMaxEntrySize() {
        return maxEntrySize;
    }

    public String getDefaultAdminUsername() {
        return defaultAdminUsername;
    }

    public String getDefaultAdminPassword() {
        return defaultAdminPassword;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public long getTransactionLockTimeoutMs() {
        return transactionLockTimeoutMs;
    }

    public boolean isCachingDisabled() {
        return maxMemoryBytes == Globals.CACHE_DISABLED;
    }

    public boolean isCacheUnlimited() {
        return maxMemoryBytes == Globals.CACHE_UNLIMITED;
    }

    public long getShutdownTimeoutMs() {
        return shutdownTimeoutMs;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public String getTlsKeystorePath() {
        return tlsKeystorePath;
    }

    public String getTlsKeystorePassword() {
        return tlsKeystorePassword;
    }

    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    public int getClusterPort() {
        return clusterPort;
    }

    public String getClusterBindAddress() {
        return clusterBindAddress;
    }

    public String getClusterAdvertisedAddress() {
        return clusterAdvertisedAddress;
    }

    public String getClusterSeeds() {
        return clusterSeeds;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getClusterExpectedSize() {
        return clusterExpectedSize;
    }

    public long getGossipIntervalMs() {
        return gossipIntervalMs;
    }

    public long getSuspectTimeoutMs() {
        return suspectTimeoutMs;
    }

    public long getDeadTimeoutMs() {
        return deadTimeoutMs;
    }

    public long getReplicationAckTimeoutMs() {
        return replicationAckTimeoutMs;
    }

    public int getVirtualNodesPerNode() {
        return virtualNodesPerNode;
    }

    public boolean isReadFallbackToLocal() {
        return readFallbackToLocal;
    }

    public boolean isScriptRoutingEnabled() {
        return scriptRoutingEnabled;
    }

    public boolean isClusterTlsEnabled() {
        return clusterTlsEnabled;
    }

    public String getClusterSecret() {
        return clusterSecret;
    }

    public long getAntiEntropyIntervalMs() {
        return antiEntropyIntervalMs;
    }

    public long getTombstoneRetentionMs() {
        return tombstoneRetentionMs;
    }

    public String getScriptTimeZone() {
        return scriptTimeZone;
    }

    public String getScriptLocale() {
        return scriptLocale;
    }

    public boolean isScriptsEnabled() {
        return scriptsEnabled;
    }

    public long getScriptInstructionBudget() {
        return scriptInstructionBudget;
    }

    public long getScriptTimeoutMs() {
        return scriptTimeoutMs;
    }

    public int getScriptMaxDepth() {
        return scriptMaxDepth;
    }

    public long getScriptMaxSourceBytes() {
        return scriptMaxSourceBytes;
    }

    public int getScriptMaxLogLines() {
        return scriptMaxLogLines;
    }

    public int getScriptMaxLogLineChars() {
        return scriptMaxLogLineChars;
    }

    public boolean isScriptTextImportEnabled() {
        return scriptTextImportEnabled;
    }

    public boolean isScriptProcedureImportEnabled() {
        return scriptProcedureImportEnabled;
    }

    public long getScriptMaxMemoryBytes() {
        return scriptMaxMemoryBytes;
    }

    public long getScriptMaxResultBytes() {
        return scriptMaxResultBytes;
    }

    public int getScriptCursorBatchSize() {
        return scriptCursorBatchSize;
    }

    public long getAggregationScriptInstructionBudget() {
        return aggregationScriptInstructionBudget;
    }

    public long getAggregationScriptTimeoutMs() {
        return aggregationScriptTimeoutMs;
    }

    public long getAggregationScriptMaxSourceBytes() {
        return aggregationScriptMaxSourceBytes;
    }

    public int getScriptCursorMaxBatchSize() {
        return scriptCursorMaxBatchSize;
    }

    public int getMaxConcurrentScripts() {
        return maxConcurrentScripts;
    }

    public long getScriptQueueWaitMs() {
        return scriptQueueWaitMs;
    }

    public int getScriptCompiledCacheSize() {
        return scriptCompiledCacheSize;
    }

    public int getProcedureCacheSize() {
        return procedureCacheSize;
    }

    public long getProcedureCacheMaxBytes() {
        return procedureCacheMaxBytes;
    }

    public long getSchemaCacheMaxBytes() {
        return schemaCacheMaxBytes;
    }

    public int getTriggerCacheMaxEntries() {
        return triggerCacheMaxEntries;
    }

    public int getMetadataMissCacheMaxEntries() {
        return metadataMissCacheMaxEntries;
    }

    public boolean isTriggersEnabled() {
        return triggersEnabled;
    }

    public int getTriggerThreads() {
        return triggerThreads;
    }

    public int getTriggerQueueSize() {
        return triggerQueueSize;
    }

    public int getTriggerMaxDepth() {
        return triggerMaxDepth;
    }

    public long getTriggerTimeoutMs() {
        return triggerTimeoutMs;
    }

    public boolean isTriggerRunLogEnabled() {
        return triggerRunLogEnabled;
    }

    public long getTriggerRunRetentionMs() {
        return triggerRunRetentionMs;
    }

    public boolean isSchedulesEnabled() {
        return schedulesEnabled;
    }

    public int getScheduleThreads() {
        return scheduleThreads;
    }

    public int getScheduleQueueSize() {
        return scheduleQueueSize;
    }

    public long getScheduleTickMs() {
        return scheduleTickMs;
    }

    public long getScheduleRefreshMs() {
        return scheduleRefreshMs;
    }

    public long getScheduleTimeoutMs() {
        return scheduleTimeoutMs;
    }

    public int getScheduleMaxPerDatabase() {
        return scheduleMaxPerDatabase;
    }

    public long getScheduleCacheMaxBytes() {
        return scheduleCacheMaxBytes;
    }
}
