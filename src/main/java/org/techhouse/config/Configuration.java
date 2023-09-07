package org.techhouse.config;

import lombok.Getter;

@Getter
public class Configuration {
    private static final Configuration config = new Configuration();

    private int port;
    private int maxConnections;
    private String filePath;
    private int backgroundProcessingThreads;
    private String logPath;
    private int maxLogFiles;

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
            }
        }
    }

    public static Configuration getInstance() {
        if (config.port == 0) {
            config.load();
        }
        return config;
    }
}
