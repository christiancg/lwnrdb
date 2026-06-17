package org.techhouse.config;

import org.techhouse.log.Logger;

public class Configuration {
    private static final Configuration config = new Configuration();
    private static final Logger logger = Logger.logFor(Configuration.class);

    private int port;
    private int maxConnections;
    private String filePath;
    private int backgroundProcessingThreads;
    private String logPath;
    private int maxLogFiles;
    private int maxPageSizeBytes;
    private int maxEntrySizeBytes;
    private String defaultAdminUsername;
    private String defaultAdminPassword;
    private long maxMemoryBytes;

    private Configuration() {
    }

    private void load() {
        final var configs = ConfigReader.loadConfiguration();
        for (var config: configs.entrySet()) {
            switch (config.getKey()) {
                case "port" -> port = Integer.parseInt(config.getValue());
                case "maxConnections" -> maxConnections = Integer.parseInt(config.getValue());
                case "filePath" -> filePath = config.getValue();
                case "backgroundProcessingThreads" -> backgroundProcessingThreads = Integer.parseInt(config.getValue());
                case "logPath" -> logPath = config.getValue();
                case "maxLogFiles" -> maxLogFiles = Integer.parseInt(config.getValue());
                case "maxPageSizeBytes" -> maxPageSizeBytes = Integer.parseInt(config.getValue());
                case "maxEntrySizeBytes" -> maxEntrySizeBytes = Integer.parseInt(config.getValue());
                case "defaultAdminUsername" -> defaultAdminUsername = config.getValue();
                case "defaultAdminPassword" -> defaultAdminPassword = config.getValue();
                case "maxMemory" -> {
                    try {
                        maxMemoryBytes = SizeParser.parse(config.getValue());
                    } catch (IllegalArgumentException e) {
                        logger.warning("Invalid maxMemory value '" + config.getValue() +
                                "', falling back to unlimited (0)");
                        maxMemoryBytes = 0L;
                    }
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

    public int getMaxPageSizeBytes() {
        return maxPageSizeBytes;
    }

    public int getMaxEntrySizeBytes() {
        return maxEntrySizeBytes;
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

    public boolean isCachingDisabled() {
        return maxMemoryBytes == Globals.CACHE_DISABLED;
    }

    public boolean isCacheUnlimited() {
        return maxMemoryBytes == Globals.CACHE_UNLIMITED;
    }
}
