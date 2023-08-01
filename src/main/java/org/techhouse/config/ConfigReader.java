package org.techhouse.config;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class ConfigReader {
    private static final Set<String> configKeys = new HashSet<>(){
        {
            add("port");
            add("maxConnections");
            add("maxFsThreads");
            add("filePath");
        }
    };
    private static final String DEFAULT_CONFIG_PATH = "/default.cfg";
    private static final String FILE_CONFIG_NAME = "lwnrdb.cfg";

    public static Map<String, String> loadConfiguration() {
        final var defaultConfigs = loadDefaultConfig();
        final var fromFile = loadFromFile();
        if (defaultConfigs != null) {
            if (fromFile != null) {
                final var missingConfigs = configKeys.stream().filter(x -> !fromFile.containsKey(x)).toList();
                if (!missingConfigs.isEmpty()) {
                    System.out.println("Warning! The following configs are missing and will be using defaults: " + String.join(",", missingConfigs));
                }
                defaultConfigs.putAll(fromFile);
            }
        }
        return defaultConfigs;
    }

    private static Map<String, String> loadFromFile() {
        var file = new File(Paths.get(".").toAbsolutePath().normalize() + "/" + FILE_CONFIG_NAME);
        if (file.exists()) {
            try {
                final var allLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                return processFromLines(allLines);
            } catch (IOException ignored) {
            }
            System.out.println("Error while loading " + FILE_CONFIG_NAME);
        } else {
            System.out.println("Warning: could not load configs, using defaults");
        }
        return null;
    }

    private static Map<String, String> loadDefaultConfig() {
        final URL url = ConfigReader.class.getResource(DEFAULT_CONFIG_PATH);
        if (url != null) {
            try {
                var file = new File(url.toURI());
                final var allLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                return processFromLines(allLines);
            } catch (URISyntaxException | IOException ignored) {
            }
        }
        System.out.println("Error while loading " + DEFAULT_CONFIG_PATH);
        return null;
    }

    private static Map<String, String> processFromLines(List<String> lines) {
        final Map<String, String> config = new HashMap<>();
        for (var line : lines) {
            final var parts = line.split("=");
            if (parts.length == 2) {
                final var key = parts[0].trim();
                final var value = parts[1].trim();
                config.put(key,value);
            } else {
                System.out.println("Not a valid property: " + line);
            }
        }
        return config;
    }
}
