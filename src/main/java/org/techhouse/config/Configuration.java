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
    private long maxCollectionCacheBytes;
    private long usageProfileRetentionMillis = 86_400_000L;
    private long memoryManagementSweepIntervalSeconds = 10L;
    private int heapHighWatermarkPercent = 80;
    private int heapLowWatermarkPercent = 65;
    private int osFreeLowWatermarkPercent = 10;
    private int osFreeHighWatermarkPercent = 20;
    private int osFreeCriticalPercent = 5;
    private long pressurePollIntervalSeconds = 2L;

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
                case "maxCollectionCache" -> {
                    try {
                        maxCollectionCacheBytes = SizeParser.parse(config.getValue());
                    } catch (IllegalArgumentException e) {
                        logger.warning("Invalid maxCollectionCache value '" + config.getValue() +
                                "', falling back to unlimited (0)");
                        maxCollectionCacheBytes = 0L;
                    }
                }
                case "usageProfileRetentionSeconds" -> {
                    try {
                        usageProfileRetentionMillis = Long.parseLong(config.getValue()) * 1000L;
                    } catch (NumberFormatException e) {
                        logger.warning("Invalid usageProfileRetentionSeconds value '" + config.getValue() +
                                "', falling back to 86400");
                    }
                }
                case "memoryManagementSweepIntervalSeconds" -> {
                    try {
                        memoryManagementSweepIntervalSeconds = Long.parseLong(config.getValue());
                    } catch (NumberFormatException e) {
                        logger.warning("Invalid memoryManagementSweepIntervalSeconds value '" + config.getValue() +
                                "', falling back to 30");
                    }
                }
                case "heapHighWatermarkPercent" -> heapHighWatermarkPercent =
                        parsePercent(config.getValue(), "heapHighWatermarkPercent", heapHighWatermarkPercent);
                case "heapLowWatermarkPercent" -> heapLowWatermarkPercent =
                        parsePercent(config.getValue(), "heapLowWatermarkPercent", heapLowWatermarkPercent);
                case "osFreeLowWatermarkPercent" -> osFreeLowWatermarkPercent =
                        parsePercent(config.getValue(), "osFreeLowWatermarkPercent", osFreeLowWatermarkPercent);
                case "osFreeHighWatermarkPercent" -> osFreeHighWatermarkPercent =
                        parsePercent(config.getValue(), "osFreeHighWatermarkPercent", osFreeHighWatermarkPercent);
                case "osFreeCriticalPercent" -> osFreeCriticalPercent =
                        parsePercent(config.getValue(), "osFreeCriticalPercent", osFreeCriticalPercent);
                case "pressurePollIntervalSeconds" -> {
                    try {
                        pressurePollIntervalSeconds = Long.parseLong(config.getValue());
                    } catch (NumberFormatException e) {
                        logger.warning("Invalid pressurePollIntervalSeconds value '" + config.getValue() +
                                "', falling back to 2");
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

    public long getMaxCollectionCacheBytes() {
        return maxCollectionCacheBytes;
    }

    public boolean isCachingDisabled() {
        return maxCollectionCacheBytes == Globals.CACHE_DISABLED;
    }

    public boolean isCacheUnlimited() {
        return maxCollectionCacheBytes == Globals.CACHE_UNLIMITED;
    }

    public long getUsageProfileRetentionMillis() {
        return usageProfileRetentionMillis;
    }

    public long getMemoryManagementSweepIntervalSeconds() {
        return memoryManagementSweepIntervalSeconds;
    }

    public int getHeapHighWatermarkPercent() {
        return heapHighWatermarkPercent;
    }

    public int getHeapLowWatermarkPercent() {
        return heapLowWatermarkPercent;
    }

    public int getOsFreeLowWatermarkPercent() {
        return osFreeLowWatermarkPercent;
    }

    public int getOsFreeHighWatermarkPercent() {
        return osFreeHighWatermarkPercent;
    }

    public int getOsFreeCriticalPercent() {
        return osFreeCriticalPercent;
    }

    public long getPressurePollIntervalSeconds() {
        return pressurePollIntervalSeconds;
    }

    public double getHeapHighWatermarkRatio() {
        return heapHighWatermarkPercent / 100.0;
    }

    public double getHeapLowWatermarkRatio() {
        return heapLowWatermarkPercent / 100.0;
    }

    public double getOsFreeLowWatermarkRatio() {
        return osFreeLowWatermarkPercent / 100.0;
    }

    public double getOsFreeHighWatermarkRatio() {
        return osFreeHighWatermarkPercent / 100.0;
    }

    public double getOsFreeCriticalRatio() {
        return osFreeCriticalPercent / 100.0;
    }

    private static int parsePercent(String value, String key, int fallback) {
        try {
            final var parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 100) {
                logger.warning("Invalid " + key + " value '" + value +
                        "' (must be 0-100), falling back to " + fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            logger.warning("Invalid " + key + " value '" + value +
                    "', falling back to " + fallback);
            return fallback;
        }
    }
}
