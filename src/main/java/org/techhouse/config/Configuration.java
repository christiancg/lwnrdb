package org.techhouse.config;

import lombok.Getter;

@Getter
public class Configuration {
    private static final Configuration config = new Configuration();

    private int port;
    private int maxConnections;
    private String filePath;

    private Configuration() {
    }

    public void load() {
        final var configs = ConfigReader.loadConfiguration();
        for (var config: configs.entrySet()) {
            switch (config.getKey()) {
                case "port" -> port = Integer.parseInt(config.getValue());
                case "maxConnections" -> maxConnections = Integer.parseInt(config.getValue());
                case "filePath" -> filePath = config.getValue();
            }
        }
    }

    public static Configuration getInstance() {
        return config;
    }
}
