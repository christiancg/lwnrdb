package org.techhouse.unit.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.techhouse.config.ConfigReader;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
            if(!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    private File getFile() throws IOException {
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try(final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
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
    public void test_loads_default_admin_credentials() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try(final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("defaultAdminUsername=adminuser");
            writer.newLine();
            writer.write("defaultAdminPassword=secretpass");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals("adminuser", config.getDefaultAdminUsername());
            assertEquals("secretpass", config.getDefaultAdminPassword());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_configuration_file_missing_or_unreadable() {
        try(MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(new HashMap<>());
            Configuration config = Configuration.getInstance();
            assertEquals(8989, config.getPort());
            assertEquals(100, config.getMaxConnections());
            assertEquals("db",config.getFilePath());
            assertEquals(10, config.getBackgroundProcessingThreads());
            assertEquals("logs", config.getLogPath());
            assertEquals(7, config.getMaxLogFiles());
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
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
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
    public void test_maxMemory_defaults_to_unlimited_when_missing() throws NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "maxMemoryBytes", 0L);
        final var minimalConfig = new HashMap<String, String>();
        minimalConfig.put("port", "8989");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(minimalConfig);
            final var config = Configuration.getInstance();
            assertEquals(0L, config.getMaxMemoryBytes());
            assertTrue(config.isCacheUnlimited());
            assertFalse(config.isCachingDisabled());
        }
    }

    @Test
    public void test_maxMemory_disabled_when_minus_one() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
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
    public void test_maxMemory_invalid_falls_back_to_unlimited() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxMemory=nonsense");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(0L, config.getMaxMemoryBytes());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }
}
