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

    public boolean isCachingDisabled() {
        return maxMemoryBytes == Globals.CACHE_DISABLED;
    }

    public boolean isCacheUnlimited() {
        return maxMemoryBytes == Globals.CACHE_UNLIMITED;
    }
}
