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

    // Configuration loads correctly with all valid default settings
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

    // Configuration loads all properties correctly from default and file sources
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

    // Configuration file is missing or unreadable
    @Test
    public void test_configuration_file_missing_or_unreadable() {
        // Simulate missing or unreadable file by mocking ConfigReader
        try(MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(new HashMap<>());
            Configuration config = Configuration.getInstance();

            // Everything should be default
            assertEquals(8989, config.getPort());
            assertEquals(100, config.getMaxConnections());
            assertEquals("db",config.getFilePath());
            assertEquals(10, config.getBackgroundProcessingThreads());
            assertEquals("logs", config.getLogPath());
            assertEquals(7, config.getMaxLogFiles());
        }
    }

    // Globals instantiation covers implicit default constructor (L5)
    @Test
    public void test_globals_instantiation() {
        assertNotNull(new Globals());
    }

    @Test
    public void test_default_memory_management_intervals() throws NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "usageProfileRetentionMillis", 86_400_000L);
        TestUtils.setPrivateField(configInstance, "memoryManagementSweepIntervalSeconds", 30L);
        final var minimalConfig = new HashMap<String, String>();
        minimalConfig.put("port", "8989");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(minimalConfig);
            final var config = Configuration.getInstance();
            assertEquals(86_400_000L, config.getUsageProfileRetentionMillis());
            assertEquals(30L, config.getMemoryManagementSweepIntervalSeconds());
        }
    }

    @Test
    public void test_loads_maxCollectionCache_humanReadable() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxCollectionCache=512Mb");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(512L * 1024L * 1024L, config.getMaxCollectionCacheBytes());
            assertFalse(config.isCachingDisabled());
            assertFalse(config.isCacheUnlimited());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_maxCollectionCache_defaults_to_unlimited_when_missing() throws NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "maxCollectionCacheBytes", 0L);
        final var minimalConfig = new HashMap<String, String>();
        minimalConfig.put("port", "8989");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(minimalConfig);
            final var config = Configuration.getInstance();
            assertEquals(0L, config.getMaxCollectionCacheBytes());
            assertTrue(config.isCacheUnlimited());
            assertFalse(config.isCachingDisabled());
        }
    }

    @Test
    public void test_maxCollectionCache_disabled_when_minus_one() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxCollectionCache=-1");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(-1L, config.getMaxCollectionCacheBytes());
            assertTrue(config.isCachingDisabled());
            assertFalse(config.isCacheUnlimited());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_maxCollectionCache_invalid_falls_back_to_unlimited() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("maxCollectionCache=nonsense");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(0L, config.getMaxCollectionCacheBytes());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_loads_usageProfileRetentionSeconds() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("usageProfileRetentionSeconds=120");
            writer.newLine();
            writer.write("memoryManagementSweepIntervalSeconds=5");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(120_000L, config.getUsageProfileRetentionMillis());
            assertEquals(5L, config.getMemoryManagementSweepIntervalSeconds());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_pressure_thresholds_have_defaults() throws NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "heapHighWatermarkPercent", 80);
        TestUtils.setPrivateField(configInstance, "heapLowWatermarkPercent", 65);
        TestUtils.setPrivateField(configInstance, "osFreeLowWatermarkPercent", 10);
        TestUtils.setPrivateField(configInstance, "osFreeHighWatermarkPercent", 20);
        TestUtils.setPrivateField(configInstance, "osFreeCriticalPercent", 5);
        TestUtils.setPrivateField(configInstance, "pressurePollIntervalSeconds", 2L);
        final var minimalConfig = new HashMap<String, String>();
        minimalConfig.put("port", "8989");
        try (MockedStatic<ConfigReader> mockConfigReader = mockStatic(ConfigReader.class)) {
            mockConfigReader.when(ConfigReader::loadConfiguration).thenReturn(minimalConfig);
            final var config = Configuration.getInstance();
            assertEquals(80, config.getHeapHighWatermarkPercent());
            assertEquals(65, config.getHeapLowWatermarkPercent());
            assertEquals(10, config.getOsFreeLowWatermarkPercent());
            assertEquals(20, config.getOsFreeHighWatermarkPercent());
            assertEquals(5, config.getOsFreeCriticalPercent());
            assertEquals(2L, config.getPressurePollIntervalSeconds());
            assertEquals(0.80, config.getHeapHighWatermarkRatio(), 1e-9);
            assertEquals(0.65, config.getHeapLowWatermarkRatio(), 1e-9);
            assertEquals(0.10, config.getOsFreeLowWatermarkRatio(), 1e-9);
            assertEquals(0.20, config.getOsFreeHighWatermarkRatio(), 1e-9);
            assertEquals(0.05, config.getOsFreeCriticalRatio(), 1e-9);
        }
    }

    @Test
    public void test_pressure_thresholds_loaded_from_file() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("heapHighWatermarkPercent=70");
            writer.newLine();
            writer.write("heapLowWatermarkPercent=50");
            writer.newLine();
            writer.write("osFreeLowWatermarkPercent=15");
            writer.newLine();
            writer.write("osFreeHighWatermarkPercent=25");
            writer.newLine();
            writer.write("osFreeCriticalPercent=3");
            writer.newLine();
            writer.write("pressurePollIntervalSeconds=4");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(70, config.getHeapHighWatermarkPercent());
            assertEquals(50, config.getHeapLowWatermarkPercent());
            assertEquals(15, config.getOsFreeLowWatermarkPercent());
            assertEquals(25, config.getOsFreeHighWatermarkPercent());
            assertEquals(3, config.getOsFreeCriticalPercent());
            assertEquals(4L, config.getPressurePollIntervalSeconds());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_invalid_pressure_thresholds_fall_back() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "heapHighWatermarkPercent", 80);
        TestUtils.setPrivateField(configInstance, "heapLowWatermarkPercent", 65);
        TestUtils.setPrivateField(configInstance, "osFreeLowWatermarkPercent", 10);
        TestUtils.setPrivateField(configInstance, "osFreeHighWatermarkPercent", 20);
        TestUtils.setPrivateField(configInstance, "osFreeCriticalPercent", 5);
        TestUtils.setPrivateField(configInstance, "pressurePollIntervalSeconds", 2L);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("heapHighWatermarkPercent=abc");
            writer.newLine();
            writer.write("heapLowWatermarkPercent=150");
            writer.newLine();
            writer.write("osFreeLowWatermarkPercent=-5");
            writer.newLine();
            writer.write("osFreeHighWatermarkPercent=xyz");
            writer.newLine();
            writer.write("osFreeCriticalPercent=200");
            writer.newLine();
            writer.write("pressurePollIntervalSeconds=foo");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(80, config.getHeapHighWatermarkPercent());
            assertEquals(65, config.getHeapLowWatermarkPercent());
            assertEquals(10, config.getOsFreeLowWatermarkPercent());
            assertEquals(20, config.getOsFreeHighWatermarkPercent());
            assertEquals(5, config.getOsFreeCriticalPercent());
            assertEquals(2L, config.getPressurePollIntervalSeconds());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }

    @Test
    public void test_invalid_usageProfileRetention_falls_back() throws IOException, NoSuchFieldException, IllegalAccessException {
        final var configInstance = Configuration.getInstance();
        TestUtils.setPrivateField(configInstance, "port", 0);
        TestUtils.setPrivateField(configInstance, "usageProfileRetentionMillis", 86_400_000L);
        TestUtils.setPrivateField(configInstance, "memoryManagementSweepIntervalSeconds", 30L);
        final var newConfigFile = new File(Globals.FILE_CONFIG_NAME);
        try (final var writer = new BufferedWriter(new FileWriter(newConfigFile, true))) {
            writer.write("usageProfileRetentionSeconds=abc");
            writer.newLine();
            writer.write("memoryManagementSweepIntervalSeconds=xyz");
            writer.newLine();
        }
        try {
            final var config = Configuration.getInstance();
            assertEquals(86_400_000L, config.getUsageProfileRetentionMillis());
            assertEquals(30L, config.getMemoryManagementSweepIntervalSeconds());
        } finally {
            if (!newConfigFile.delete()) {
                fail("Failed deleting temp test file");
            }
        }
    }
}