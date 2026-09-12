package org.techhouse.config;

import java.util.Arrays;
import java.util.List;

public enum ConfigKey {
    // @formatter:off
    PORT("port", ValueType.INT, "8989", Rule.range(1, 65535)),
    MAX_CONNECTIONS("maxConnections", ValueType.INT, "100", Rule.atLeast(0)),
    BACKGROUND_PROCESSING_THREADS("backgroundProcessingThreads", ValueType.INT, "10", Rule.atLeast(1)),
    MAX_LOG_FILES("maxLogFiles", ValueType.INT, "7", Rule.atLeast(1)),
    MAX_PAGE_SIZE("maxPageSize", ValueType.SIZE, "2Mb", Rule.positiveSize()),
    MAX_ENTRY_SIZE("maxEntrySize", ValueType.SIZE, "1Mb", Rule.positiveSize()),
    MAX_MEMORY("maxMemory", ValueType.SIZE, "512mb", Rule.memory()),
    TRANSACTION_LOCK_TIMEOUT_MS("transactionLockTimeoutMs", ValueType.LONG, "5000", Rule.atLeast(1)),
    SHUTDOWN_TIMEOUT_MS("shutdownTimeoutMs", ValueType.LONG, "15000", Rule.atLeast(1)),
    TLS_ENABLED("tlsEnabled", ValueType.BOOLEAN, "false", Rule.bool()),
    CLUSTER_ENABLED("clusterEnabled", ValueType.BOOLEAN, "false", Rule.bool()),
    CLUSTER_PORT("clusterPort", ValueType.INT, "9990", Rule.range(1, 65535)),
    CLUSTER_EXPECTED_SIZE("clusterExpectedSize", ValueType.INT, "1", Rule.atLeast(1)),
    GOSSIP_INTERVAL_MS("gossipIntervalMs", ValueType.LONG, "1000", Rule.atLeast(1)),
    SUSPECT_TIMEOUT_MS("suspectTimeoutMs", ValueType.LONG, "5000", Rule.atLeast(1)),
    DEAD_TIMEOUT_MS("deadTimeoutMs", ValueType.LONG, "15000", Rule.atLeast(1)),
    REPLICATION_ACK_TIMEOUT_MS("replicationAckTimeoutMs", ValueType.LONG, "5000", Rule.atLeast(1)),
    VIRTUAL_NODES_PER_NODE("virtualNodesPerNode", ValueType.INT, "128", Rule.atLeast(1)),
    READ_FALLBACK_TO_LOCAL("readFallbackToLocal", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_ROUTING_ENABLED("scriptRoutingEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_LOCALITY_WEIGHT("scriptLocalityWeight", ValueType.INT, "50", Rule.range(0, 100)),
    CLUSTER_TLS_ENABLED("clusterTlsEnabled", ValueType.BOOLEAN, "false", Rule.bool()),
    ANTI_ENTROPY_INTERVAL_MS("antiEntropyIntervalMs", ValueType.LONG, "60000", Rule.atLeast(1)),
    TOMBSTONE_RETENTION_MS("tombstoneRetentionMs", ValueType.LONG, "86400000", Rule.atLeast(1)),
    SCRIPTS_ENABLED("scriptsEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_INSTRUCTION_BUDGET("scriptInstructionBudget", ValueType.LONG, "10000000", Rule.atLeast(1)),
    SCRIPT_TIMEOUT_MS("scriptTimeoutMs", ValueType.LONG, "5000", Rule.atLeast(1)),
    SCRIPT_MAX_DEPTH("scriptMaxDepth", ValueType.INT, "200", Rule.atLeast(1)),
    SCRIPT_MAX_SOURCE_BYTES("scriptMaxSourceBytes", ValueType.SIZE, "256Kb", Rule.positiveSize()),
    SCRIPT_MAX_LOG_LINES("scriptMaxLogLines", ValueType.INT, "1000", Rule.atLeast(1)),
    SCRIPT_MAX_LOG_LINE_CHARS("scriptMaxLogLineChars", ValueType.INT, "4096", Rule.atLeast(1)),
    SCRIPT_TEXT_IMPORT_ENABLED("scriptTextImportEnabled", ValueType.BOOLEAN, "false", Rule.bool()),
    SCRIPT_PROCEDURE_IMPORT_ENABLED("scriptProcedureImportEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_MAX_MEMORY_BYTES("scriptMaxMemoryBytes", ValueType.SIZE, "64Mb", Rule.positiveSize()),
    SCRIPT_MAX_RESULT_BYTES("scriptMaxResultBytes", ValueType.SIZE, "16Mb", Rule.positiveSize()),
    SCRIPT_CURSOR_BATCH_SIZE("scriptCursorBatchSize", ValueType.INT, "500", Rule.atLeast(1)),
    SCRIPT_CURSOR_MAX_BATCH_SIZE("scriptCursorMaxBatchSize", ValueType.INT, "5000", Rule.atLeast(1)),
    AGGREGATION_SCRIPT_INSTRUCTION_BUDGET("aggregationScriptInstructionBudget", ValueType.LONG, "1000000", Rule.atLeast(1)),
    AGGREGATION_SCRIPT_TIMEOUT_MS("aggregationScriptTimeoutMs", ValueType.LONG, "2000", Rule.atLeast(1)),
    AGGREGATION_SCRIPT_MAX_SOURCE_BYTES("aggregationScriptMaxSourceBytes", ValueType.SIZE, "16Kb", Rule.positiveSize()),
    MAX_CONCURRENT_SCRIPTS("maxConcurrentScripts", ValueType.INT, "16", Rule.atLeast(0)),
    SCRIPT_QUEUE_WAIT_MS("scriptQueueWaitMs", ValueType.LONG, "250", Rule.atLeast(0)),
    MAX_CONCURRENT_SCRIPTS_PER_USER("maxConcurrentScriptsPerUser", ValueType.INT, "0", Rule.atLeast(0)),
    MAX_CONCURRENT_SCRIPTS_PER_DATABASE("maxConcurrentScriptsPerDatabase", ValueType.INT, "0", Rule.atLeast(0)),
    SCRIPT_RUN_HISTORY_ENABLED("scriptRunHistoryEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_RUN_HISTORY_RETENTION_MS("scriptRunHistoryRetentionMs", ValueType.LONG, "604800000", Rule.atLeast(1)),
    SCRIPT_RUN_HISTORY_INCLUDE_LOGS("scriptRunHistoryIncludeLogs", ValueType.BOOLEAN, "false", Rule.bool()),
    SCRIPT_RUN_HISTORY_MAX_ERROR_CHARS("scriptRunHistoryMaxErrorChars", ValueType.INT, "2000", Rule.atLeast(1)),
    SCRIPT_FETCH_ENABLED("scriptFetchEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCRIPT_FETCH_TIMEOUT_MS("scriptFetchTimeoutMs", ValueType.LONG, "5000", Rule.atLeast(1)),
    SCRIPT_FETCH_MAX_RESPONSE_BYTES("scriptFetchMaxResponseBytes", ValueType.SIZE, "1Mb", Rule.positiveSize()),
    PROCEDURE_CACHE_SIZE("procedureCacheSize", ValueType.INT, "128", Rule.atLeast(0)),
    METADATA_CACHE_MAX_BYTES("metadataCacheMaxBytes", ValueType.SIZE, "72Mb", Rule.positiveSize()),
    METADATA_CACHE_MAX_ENTRIES("metadataCacheMaxEntries", ValueType.INT, "4096", Rule.atLeast(0)),
    TRIGGERS_ENABLED("triggersEnabled", ValueType.BOOLEAN, "false", Rule.bool()),
    TRIGGER_THREADS("triggerThreads", ValueType.INT, "2", Rule.atLeast(1)),
    TRIGGER_QUEUE_SIZE("triggerQueueSize", ValueType.INT, "10000", Rule.atLeast(1)),
    TRIGGER_MAX_DEPTH("triggerMaxDepth", ValueType.INT, "3", Rule.atLeast(0)),
    TRIGGER_TIMEOUT_MS("triggerTimeoutMs", ValueType.LONG, "1000", Rule.atLeast(1)),
    TRIGGER_RUN_LOG_ENABLED("triggerRunLogEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    TRIGGER_RUN_RETENTION_MS("triggerRunRetentionMs", ValueType.LONG, "86400000", Rule.atLeast(1)),
    TRIGGER_MAX_ATTEMPTS("triggerMaxAttempts", ValueType.INT, "3", Rule.atLeast(1)),
    TRIGGER_RETRY_BACKOFF_MS("triggerRetryBackoffMs", ValueType.LONG, "1000", Rule.atLeast(0)),
    TRIGGER_RETRY_MAX_BACKOFF_MS("triggerRetryMaxBackoffMs", ValueType.LONG, "60000", Rule.atLeast(0)),
    TRIGGER_DEAD_LETTER_RETENTION_MS("triggerDeadLetterRetentionMs", ValueType.LONG, "604800000", Rule.atLeast(1)),
    BEFORE_HOOK_INSTRUCTION_BUDGET("beforeHookInstructionBudget", ValueType.LONG, "200000", Rule.atLeast(1)),
    BEFORE_HOOK_TIMEOUT_MS("beforeHookTimeoutMs", ValueType.LONG, "200", Rule.atLeast(1)),
    SCHEDULES_ENABLED("schedulesEnabled", ValueType.BOOLEAN, "true", Rule.bool()),
    SCHEDULE_THREADS("scheduleThreads", ValueType.INT, "2", Rule.atLeast(1)),
    SCHEDULE_QUEUE_SIZE("scheduleQueueSize", ValueType.INT, "100", Rule.atLeast(1)),
    SCHEDULE_TICK_MS("scheduleTickMs", ValueType.LONG, "1000", Rule.atLeast(1)),
    SCHEDULE_REFRESH_MS("scheduleRefreshMs", ValueType.LONG, "60000", Rule.atLeast(1)),
    SCHEDULE_TIMEOUT_MS("scheduleTimeoutMs", ValueType.LONG, "30000", Rule.atLeast(1)),
    SCHEDULE_MAX_PER_DATABASE("scheduleMaxPerDatabase", ValueType.INT, "100", Rule.atLeast(1)),
    FILE_PATH("filePath", ValueType.STRING, "db", Rule.none()),
    LOG_PATH("logPath", ValueType.STRING, "logs", Rule.none()),
    DEFAULT_ADMIN_USERNAME("defaultAdminUsername", ValueType.STRING, "admin", Rule.none()),
    DEFAULT_ADMIN_PASSWORD("defaultAdminPassword", ValueType.STRING, "administrator", Rule.none()),
    TLS_KEYSTORE_PATH("tlsKeystorePath", ValueType.STRING, "certs/lwnrdb.p12", Rule.none()),
    TLS_KEYSTORE_PASSWORD("tlsKeystorePassword", ValueType.STRING, "change_it", Rule.none()),
    CLUSTER_BIND_ADDRESS("clusterBindAddress", ValueType.STRING, "0.0.0.0", Rule.none()),
    CLUSTER_ADVERTISED_ADDRESS("clusterAdvertisedAddress", ValueType.STRING, "127.0.0.1", Rule.none()),
    CLUSTER_SEEDS("clusterSeeds", ValueType.STRING, "", Rule.none()),
    NODE_ID("nodeId", ValueType.STRING, "", Rule.none()),
    CLUSTER_SECRET("clusterSecret", ValueType.STRING, "", Rule.none()),
    SCRIPT_TIME_ZONE("scriptTimeZone", ValueType.STRING, "UTC", Rule.none()),
    SCRIPT_LOCALE("scriptLocale", ValueType.STRING, "en-US", Rule.none()),
    SCRIPT_RUN_HISTORY_KINDS("scriptRunHistoryKinds", ValueType.STRING, "CALL_PROCEDURE,TRIGGER,SCHEDULE", Rule.none()),
    SCRIPT_FETCH_ALLOWLIST("scriptFetchAllowlist", ValueType.STRING, "*", Rule.none());
    // @formatter:on

    private final String key;
    private final ValueType type;
    private final String defaultValue;
    private final Rule rule;

    ConfigKey(String key, ValueType type, String defaultValue, Rule rule) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.rule = rule;
    }

    public String key() {
        return key;
    }

    public ValueType type() {
        return type;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String validate(String value) {
        return rule.check().apply(value);
    }

    public static List<ConfigKey> all() {
        return Arrays.asList(values());
    }
}
