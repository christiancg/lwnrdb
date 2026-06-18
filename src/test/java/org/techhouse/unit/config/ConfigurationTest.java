package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.config.ConfigReader;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.test.TestUtils;

public class ConfigurationTest {

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

    @Test
    public void test_loads_with_valid_default_settings() {
        Configuration config = Configuration.getInstance();
        assertEquals(8989, config.getPort());
        assertEquals(100, config.getMaxConnections());
        assertEquals("db", config.getFilePath());
        assertEquals(10, config.getBackgroundProcessingThreads());
        assertEquals("logs", config.getLogPath());
        assertEquals(7, config.getMaxLogFiles());
    }

    @Test
    public void test_loads_all_properties_correctly() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = getFile();
        try {
            Configuration config = Configuration.getInstance();
            assertEquals(1111, config.getPort());
            assertEquals(1, config.getMaxConnections());
            assertEquals("test", config.getFilePath());
            assertEquals(1, config.getBackgroundProcessingThreads());
            assertEquals("test_log", config.getLogPath());
            assertEquals(1, config.getMaxLogFiles());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    private File getFile() throws IOException {
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("port=1111");
            writer.newLine();
            writer.write("maxConnections=1");
            writer.newLine();
            writer.write("filePath=test");
            writer.newLine();
            writer.write("backgroundProcessingThreads=1");
            writer.newLine();
            writer.write("logPath=test_log");
            writer.newLine();
            writer.write("maxLogFiles=1");
            writer.newLine();
        }
        return newConfigFile;
    }

    @Test
    public void test_loads_default_admin_credentials()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("defaultAdminUsername=admin_user");
            writer.newLine();
            writer.write("defaultAdminPassword=secret_pass");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals("admin_user", config.getDefaultAdminUsername());
            assertEquals("secret_pass", config.getDefaultAdminPassword());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    private static Map<String, String> fullValidConfig() {
        final var map = new HashMap<String, String>();
        map.put("port", "8989");
        map.put("maxConnections", "100");
        map.put("filePath", "db");
        map.put("backgroundProcessingThreads", "10");
        map.put("logPath", "logs");
        map.put("maxLogFiles", "7");
        map.put("maxPageSize", "2Mb");
        map.put("maxEntrySize", "1Mb");
        map.put("defaultAdminUsername", "admin");
        map.put("defaultAdminPassword", "administrator");
        map.put("maxMemory", "512mb");
        return map;
    }

    @Test
    public void test_configuration_loads_a_full_valid_map() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "port", 0);
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(fullValidConfig());
            Configuration config = Configuration.getInstance();
            assertEquals(8989, config.getPort());
            assertEquals(100, config.getMaxConnections());
            assertEquals("db", config.getFilePath());
            assertEquals(10, config.getBackgroundProcessingThreads());
            assertEquals("logs", config.getLogPath());
            assertEquals(7, config.getMaxLogFiles());
            assertEquals(2L * 1024L * 1024L, config.getMaxPageSize());
            assertEquals(1024L * 1024L, config.getMaxEntrySize());
        }
    }

    @Test
    public void test_configuration_throws_when_validation_fails() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.setPrivateField(Configuration.getInstance(), "port", 0);
        final var invalid = fullValidConfig();
        invalid.put("port", "not-a-number");
        invalid.put("maxConnections", "-5");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(invalid);
            assertThrows(InvalidConfigurationException.class, Configuration::getInstance);
        }
    }

    @Test
    public void test_globals_instantiation() {
        assertNotNull(new Globals());
    }

    @Test
    public void test_loads_maxMemory_humanReadable() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxMemory=512Mb");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(512L * 1024L * 1024L, config.getMaxMemoryBytes());
            assertFalse(config.isCachingDisabled());
            assertFalse(config.isCacheUnlimited());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_maxMemory_unlimited_when_zero() throws NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "maxMemoryBytes", 0L);
        final var configMap = fullValidConfig();
        configMap.put("maxMemory", "0");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(configMap);
            final var config = Configuration.getInstance();
            assertEquals(0L, config.getMaxMemoryBytes());
            assertTrue(config.isCacheUnlimited());
            assertFalse(config.isCachingDisabled());
        }
    }

    @Test
    public void test_maxMemory_disabled_when_minus_one()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxMemory=-1");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(-1L, config.getMaxMemoryBytes());
            assertTrue(config.isCachingDisabled());
            assertFalse(config.isCacheUnlimited());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_maxMemory_invalid_throws() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxMemory=nonsense");
            writer.newLine();
        }
        try {
            assertThrows(InvalidConfigurationException.class, Configuration::getInstance);
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }
}
