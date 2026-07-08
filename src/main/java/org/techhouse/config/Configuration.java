package org.techhouse.config;

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
    private boolean clusterTlsEnabled;
    private String clusterSecret;

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
        for (var config : configs.entrySet()) {
            switch (config.getKey()) {
                case "port" -> port = Integer.parseInt(config.getValue());
                case "maxConnections" -> maxConnections = Integer.parseInt(config.getValue());
                case "filePath" -> filePath = config.getValue();
                case "backgroundProcessingThreads" -> backgroundProcessingThreads = Integer.parseInt(config.getValue());
                case "logPath" -> logPath = config.getValue();
                case "maxLogFiles" -> maxLogFiles = Integer.parseInt(config.getValue());
                case "maxPageSize" -> maxPageSize = SizeParser.parse(config.getValue());
                case "maxEntrySize" -> maxEntrySize = SizeParser.parse(config.getValue());
                case "defaultAdminUsername" -> defaultAdminUsername = config.getValue();
                case "defaultAdminPassword" -> defaultAdminPassword = config.getValue();
                case "maxMemory" -> maxMemoryBytes = SizeParser.parse(config.getValue());
                case "transactionLockTimeoutMs" -> transactionLockTimeoutMs = Long.parseLong(config.getValue());
                case "tlsEnabled" -> tlsEnabled = Boolean.parseBoolean(config.getValue());
                case "tlsKeystorePath" -> tlsKeystorePath = config.getValue();
                case "tlsKeystorePassword" -> tlsKeystorePassword = config.getValue();
                case "clusterEnabled" -> clusterEnabled = Boolean.parseBoolean(config.getValue());
                case "clusterPort" -> clusterPort = Integer.parseInt(config.getValue());
                case "clusterBindAddress" -> clusterBindAddress = config.getValue();
                case "clusterAdvertisedAddress" -> clusterAdvertisedAddress = config.getValue();
                case "clusterSeeds" -> clusterSeeds = config.getValue();
                case "nodeId" -> nodeId = config.getValue();
                case "clusterExpectedSize" -> clusterExpectedSize = Integer.parseInt(config.getValue());
                case "gossipIntervalMs" -> gossipIntervalMs = Long.parseLong(config.getValue());
                case "suspectTimeoutMs" -> suspectTimeoutMs = Long.parseLong(config.getValue());
                case "deadTimeoutMs" -> deadTimeoutMs = Long.parseLong(config.getValue());
                case "replicationAckTimeoutMs" -> replicationAckTimeoutMs = Long.parseLong(config.getValue());
                case "virtualNodesPerNode" -> virtualNodesPerNode = Integer.parseInt(config.getValue());
                case "readFallbackToLocal" -> readFallbackToLocal = Boolean.parseBoolean(config.getValue());
                case "clusterTlsEnabled" -> clusterTlsEnabled = Boolean.parseBoolean(config.getValue());
                case "clusterSecret" -> clusterSecret = config.getValue();
                default -> {
                }
            }
        }
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

    public boolean isClusterTlsEnabled() {
        return clusterTlsEnabled;
    }

    public String getClusterSecret() {
        return clusterSecret;
    }
}
