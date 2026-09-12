package org.techhouse.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.log.Logger;

public final class Configuration {
    private static final Configuration config = new Configuration();
    private final Map<ConfigKey, String> values = new EnumMap<>(ConfigKey.class);
    private final Map<ConfigKey, Object> resolved = new EnumMap<>(ConfigKey.class);
    private boolean loading;
    private static final Logger logger = Logger.logFor(Configuration.class);

    private Configuration() {
    }

    // The flag is read by getInstance, which PMD's per-method dataflow cannot see.
    @SuppressWarnings("PMD.UnusedAssignment")
    private void load() {
        loading = true;
        resolved.clear();
        try {
            final var configs = ConfigReader.loadConfiguration();
            final var errors = ConfigurationValidator.validate(configs);
            if (!errors.isEmpty()) {
                logger.fatal("Configuration validation failed, the application will not start:" + Globals.NEWLINE
                        + String.join(Globals.NEWLINE, errors));
                throw new InvalidConfigurationException(errors);
            }
            for (final var key : ConfigKey.all()) {
                values.put(key, configs.getOrDefault(key.key(), key.defaultValue()));
            }
        } finally {
            loading = false;
        }
    }

    private int intValue(ConfigKey key) {
        return (int) resolved.computeIfAbsent(key, k -> Integer.parseInt(values.get(k).trim()));
    }

    private long longValue(ConfigKey key) {
        return (long) resolved.computeIfAbsent(key, k -> Long.parseLong(values.get(k).trim()));
    }

    private long sizeValue(ConfigKey key) {
        return (long) resolved.computeIfAbsent(key, k -> SizeParser.parse(values.get(k)));
    }

    private boolean booleanValue(ConfigKey key) {
        return (boolean) resolved.computeIfAbsent(key, k -> Boolean.parseBoolean(values.get(k).trim()));
    }

    public static Configuration getInstance() {
        if (!config.loading && (config.values.isEmpty() || "0".equals(config.values.get(ConfigKey.PORT)))) {
            config.load();
        }
        return config;
    }

    public int getPort() {
        return intValue(ConfigKey.PORT);
    }

    public int getMaxConnections() {
        return intValue(ConfigKey.MAX_CONNECTIONS);
    }

    public String getFilePath() {
        return values.get(ConfigKey.FILE_PATH);
    }

    public int getBackgroundProcessingThreads() {
        return intValue(ConfigKey.BACKGROUND_PROCESSING_THREADS);
    }

    public String getLogPath() {
        return values.get(ConfigKey.LOG_PATH);
    }

    public int getMaxLogFiles() {
        return intValue(ConfigKey.MAX_LOG_FILES);
    }

    public long getMaxPageSize() {
        return sizeValue(ConfigKey.MAX_PAGE_SIZE);
    }

    public long getMaxEntrySize() {
        return sizeValue(ConfigKey.MAX_ENTRY_SIZE);
    }

    public String getDefaultAdminUsername() {
        return values.get(ConfigKey.DEFAULT_ADMIN_USERNAME);
    }

    public String getDefaultAdminPassword() {
        return values.get(ConfigKey.DEFAULT_ADMIN_PASSWORD);
    }

    public long getMaxMemoryBytes() {
        return sizeValue(ConfigKey.MAX_MEMORY);
    }

    public long getTransactionLockTimeoutMs() {
        return longValue(ConfigKey.TRANSACTION_LOCK_TIMEOUT_MS);
    }

    public boolean isCachingDisabled() {
        return getMaxMemoryBytes() == Globals.CACHE_DISABLED;
    }

    public boolean isCacheUnlimited() {
        return getMaxMemoryBytes() == Globals.CACHE_UNLIMITED;
    }

    public long getShutdownTimeoutMs() {
        return longValue(ConfigKey.SHUTDOWN_TIMEOUT_MS);
    }

    public boolean isTlsEnabled() {
        return booleanValue(ConfigKey.TLS_ENABLED);
    }

    public String getTlsKeystorePath() {
        return values.get(ConfigKey.TLS_KEYSTORE_PATH);
    }

    public String getTlsKeystorePassword() {
        return values.get(ConfigKey.TLS_KEYSTORE_PASSWORD);
    }

    public boolean isClusterEnabled() {
        return booleanValue(ConfigKey.CLUSTER_ENABLED);
    }

    public int getClusterPort() {
        return intValue(ConfigKey.CLUSTER_PORT);
    }

    public String getClusterBindAddress() {
        return values.get(ConfigKey.CLUSTER_BIND_ADDRESS);
    }

    public String getClusterAdvertisedAddress() {
        return values.get(ConfigKey.CLUSTER_ADVERTISED_ADDRESS);
    }

    public String getClusterSeeds() {
        return values.get(ConfigKey.CLUSTER_SEEDS);
    }

    public String getNodeId() {
        return values.get(ConfigKey.NODE_ID);
    }

    public int getClusterExpectedSize() {
        return intValue(ConfigKey.CLUSTER_EXPECTED_SIZE);
    }

    public long getGossipIntervalMs() {
        return longValue(ConfigKey.GOSSIP_INTERVAL_MS);
    }

    public long getSuspectTimeoutMs() {
        return longValue(ConfigKey.SUSPECT_TIMEOUT_MS);
    }

    public long getDeadTimeoutMs() {
        return longValue(ConfigKey.DEAD_TIMEOUT_MS);
    }

    public long getReplicationAckTimeoutMs() {
        return longValue(ConfigKey.REPLICATION_ACK_TIMEOUT_MS);
    }

    public int getVirtualNodesPerNode() {
        return intValue(ConfigKey.VIRTUAL_NODES_PER_NODE);
    }

    public boolean isReadFallbackToLocal() {
        return booleanValue(ConfigKey.READ_FALLBACK_TO_LOCAL);
    }

    public boolean isScriptRoutingEnabled() {
        return booleanValue(ConfigKey.SCRIPT_ROUTING_ENABLED);
    }

    public int getScriptLocalityWeight() {
        return intValue(ConfigKey.SCRIPT_LOCALITY_WEIGHT);
    }

    public boolean isClusterTlsEnabled() {
        return booleanValue(ConfigKey.CLUSTER_TLS_ENABLED);
    }

    public String getClusterSecret() {
        return values.get(ConfigKey.CLUSTER_SECRET);
    }

    public long getAntiEntropyIntervalMs() {
        return longValue(ConfigKey.ANTI_ENTROPY_INTERVAL_MS);
    }

    public long getTombstoneRetentionMs() {
        return longValue(ConfigKey.TOMBSTONE_RETENTION_MS);
    }

    public String getScriptTimeZone() {
        return values.get(ConfigKey.SCRIPT_TIME_ZONE);
    }

    public String getScriptLocale() {
        return values.get(ConfigKey.SCRIPT_LOCALE);
    }

    public boolean isScriptsEnabled() {
        return booleanValue(ConfigKey.SCRIPTS_ENABLED);
    }

    public long getScriptInstructionBudget() {
        return longValue(ConfigKey.SCRIPT_INSTRUCTION_BUDGET);
    }

    public long getScriptTimeoutMs() {
        return longValue(ConfigKey.SCRIPT_TIMEOUT_MS);
    }

    public int getScriptMaxDepth() {
        return intValue(ConfigKey.SCRIPT_MAX_DEPTH);
    }

    public long getScriptMaxSourceBytes() {
        return sizeValue(ConfigKey.SCRIPT_MAX_SOURCE_BYTES);
    }

    public int getScriptMaxLogLines() {
        return intValue(ConfigKey.SCRIPT_MAX_LOG_LINES);
    }

    public int getScriptMaxLogLineChars() {
        return intValue(ConfigKey.SCRIPT_MAX_LOG_LINE_CHARS);
    }

    public boolean isScriptTextImportEnabled() {
        return booleanValue(ConfigKey.SCRIPT_TEXT_IMPORT_ENABLED);
    }

    public boolean isScriptProcedureImportEnabled() {
        return booleanValue(ConfigKey.SCRIPT_PROCEDURE_IMPORT_ENABLED);
    }

    public long getScriptMaxMemoryBytes() {
        return sizeValue(ConfigKey.SCRIPT_MAX_MEMORY_BYTES);
    }

    public long getScriptMaxResultBytes() {
        return sizeValue(ConfigKey.SCRIPT_MAX_RESULT_BYTES);
    }

    public int getScriptCursorBatchSize() {
        return intValue(ConfigKey.SCRIPT_CURSOR_BATCH_SIZE);
    }

    public long getAggregationScriptInstructionBudget() {
        return longValue(ConfigKey.AGGREGATION_SCRIPT_INSTRUCTION_BUDGET);
    }

    public long getAggregationScriptTimeoutMs() {
        return longValue(ConfigKey.AGGREGATION_SCRIPT_TIMEOUT_MS);
    }

    public long getAggregationScriptMaxSourceBytes() {
        return sizeValue(ConfigKey.AGGREGATION_SCRIPT_MAX_SOURCE_BYTES);
    }

    public int getScriptCursorMaxBatchSize() {
        return intValue(ConfigKey.SCRIPT_CURSOR_MAX_BATCH_SIZE);
    }

    public int getMaxConcurrentScripts() {
        return intValue(ConfigKey.MAX_CONCURRENT_SCRIPTS);
    }

    public long getScriptQueueWaitMs() {
        return longValue(ConfigKey.SCRIPT_QUEUE_WAIT_MS);
    }

    public int getMaxConcurrentScriptsPerUser() {
        return intValue(ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_USER);
    }

    public int getMaxConcurrentScriptsPerDatabase() {
        return intValue(ConfigKey.MAX_CONCURRENT_SCRIPTS_PER_DATABASE);
    }

    public boolean isScriptRunHistoryEnabled() {
        return booleanValue(ConfigKey.SCRIPT_RUN_HISTORY_ENABLED);
    }

    public String getScriptRunHistoryKinds() {
        return values.get(ConfigKey.SCRIPT_RUN_HISTORY_KINDS);
    }

    public long getScriptRunHistoryRetentionMs() {
        return longValue(ConfigKey.SCRIPT_RUN_HISTORY_RETENTION_MS);
    }

    public boolean isScriptRunHistoryIncludeLogs() {
        return booleanValue(ConfigKey.SCRIPT_RUN_HISTORY_INCLUDE_LOGS);
    }

    public int getScriptRunHistoryMaxErrorChars() {
        return intValue(ConfigKey.SCRIPT_RUN_HISTORY_MAX_ERROR_CHARS);
    }

    public boolean isScriptFetchEnabled() {
        return booleanValue(ConfigKey.SCRIPT_FETCH_ENABLED);
    }

    public List<String> getScriptFetchAllowlist() {
        if (values.get(ConfigKey.SCRIPT_FETCH_ALLOWLIST) == null
                || values.get(ConfigKey.SCRIPT_FETCH_ALLOWLIST).isBlank()) {
            return List.of();
        }
        final var hosts = new ArrayList<String>();
        for (final var entry : values.get(ConfigKey.SCRIPT_FETCH_ALLOWLIST).split(",")) {
            final var trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return List.copyOf(hosts);
    }

    public long getScriptFetchTimeoutMs() {
        return longValue(ConfigKey.SCRIPT_FETCH_TIMEOUT_MS);
    }

    public long getScriptFetchMaxResponseBytes() {
        return sizeValue(ConfigKey.SCRIPT_FETCH_MAX_RESPONSE_BYTES);
    }

    public int getProcedureCacheSize() {
        return intValue(ConfigKey.PROCEDURE_CACHE_SIZE);
    }

    public long getMetadataCacheMaxBytes() {
        return sizeValue(ConfigKey.METADATA_CACHE_MAX_BYTES);
    }

    public int getMetadataCacheMaxEntries() {
        return intValue(ConfigKey.METADATA_CACHE_MAX_ENTRIES);
    }

    public boolean isTriggersEnabled() {
        return booleanValue(ConfigKey.TRIGGERS_ENABLED);
    }

    public int getTriggerThreads() {
        return intValue(ConfigKey.TRIGGER_THREADS);
    }

    public int getTriggerQueueSize() {
        return intValue(ConfigKey.TRIGGER_QUEUE_SIZE);
    }

    public int getTriggerMaxDepth() {
        return intValue(ConfigKey.TRIGGER_MAX_DEPTH);
    }

    public long getTriggerTimeoutMs() {
        return longValue(ConfigKey.TRIGGER_TIMEOUT_MS);
    }

    public boolean isTriggerRunLogEnabled() {
        return booleanValue(ConfigKey.TRIGGER_RUN_LOG_ENABLED);
    }

    public int getTriggerMaxAttempts() {
        return intValue(ConfigKey.TRIGGER_MAX_ATTEMPTS);
    }

    public long getTriggerRetryBackoffMs() {
        return longValue(ConfigKey.TRIGGER_RETRY_BACKOFF_MS);
    }

    public long getTriggerRetryMaxBackoffMs() {
        return longValue(ConfigKey.TRIGGER_RETRY_MAX_BACKOFF_MS);
    }

    public long getTriggerDeadLetterRetentionMs() {
        return longValue(ConfigKey.TRIGGER_DEAD_LETTER_RETENTION_MS);
    }

    public long getTriggerRunRetentionMs() {
        return longValue(ConfigKey.TRIGGER_RUN_RETENTION_MS);
    }

    public long getBeforeHookInstructionBudget() {
        return longValue(ConfigKey.BEFORE_HOOK_INSTRUCTION_BUDGET);
    }

    public long getBeforeHookTimeoutMs() {
        return longValue(ConfigKey.BEFORE_HOOK_TIMEOUT_MS);
    }

    public boolean isSchedulesEnabled() {
        return booleanValue(ConfigKey.SCHEDULES_ENABLED);
    }

    public int getScheduleThreads() {
        return intValue(ConfigKey.SCHEDULE_THREADS);
    }

    public int getScheduleQueueSize() {
        return intValue(ConfigKey.SCHEDULE_QUEUE_SIZE);
    }

    public long getScheduleTickMs() {
        return longValue(ConfigKey.SCHEDULE_TICK_MS);
    }

    public long getScheduleRefreshMs() {
        return longValue(ConfigKey.SCHEDULE_REFRESH_MS);
    }

    public long getScheduleTimeoutMs() {
        return longValue(ConfigKey.SCHEDULE_TIMEOUT_MS);
    }

    public int getScheduleMaxPerDatabase() {
        return intValue(ConfigKey.SCHEDULE_MAX_PER_DATABASE);
    }

}
