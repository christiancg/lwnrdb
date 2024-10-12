package org.techhouse.unit.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigReader;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigReaderTest {

    @BeforeEach
    public void setUp() {
        final var originalConfigFile = new File(Globals.FILE_CONFIG_NAME);
        if (originalConfigFile.exists()) {
            if (!originalConfigFile.renameTo(new File(Globals.FILE_CONFIG_NAME + ".moved"))) {
                fail("Could not rename config file");
            }
        }
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        final var movedConfigFile = new File(Globals.FILE_CONFIG_NAME + ".moved");
        if (movedConfigFile.exists()) {
            if (!movedConfigFile.renameTo(new File(Globals.FILE_CONFIG_NAME))) {
                fail("Failed returning to original file");
            }
        }
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        Configuration.getInstance();
    }

    // Successfully loads configuration from default file when no external file is present
    @Test
    public void test_load_configuration_from_default_file() {
        // Arrange
        Map<String, String> expectedConfig = new HashMap<>();
        expectedConfig.put("port", "8989");
        expectedConfig.put("maxConnections", "100");
        expectedConfig.put("filePath", "db");
        expectedConfig.put("backgroundProcessingThreads", "10");
        expectedConfig.put("logPath", "logs");
        expectedConfig.put("maxLogFiles", "7");

        // Act
        Map<String, String> actualConfig = ConfigReader.loadConfiguration();

        // Assert
        assertEquals(expectedConfig, actualConfig);
    }

    // Handles empty configuration files gracefully
    @Test
    public void test_handle_empty_configuration_file() {
        // Arrange
        File configFile = new File(Paths.get(".").toAbsolutePath().normalize() + Globals.FILE_SEPARATOR + Globals.FILE_CONFIG_NAME);
        try {
            try {
                Files.write(configFile.toPath(), Collections.emptyList(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                fail("Failed to create empty configuration file for testing.");
            }

            // Act
            Map<String, String> actualConfig = ConfigReader.loadConfiguration();

            // Assert
            assertNotNull(actualConfig);
            assertFalse(actualConfig.isEmpty()); //should have defaults
        } finally {
            if (!configFile.delete()) {
                fail("Failed to delete empty configuration file.");
            }
        }
    }
}