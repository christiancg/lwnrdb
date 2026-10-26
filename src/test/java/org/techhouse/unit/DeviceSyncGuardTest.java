package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.DeviceSyncGuard;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.log.LogWriter;
import org.techhouse.test.TestUtils;

public class DeviceSyncGuardTest {
    private final Configuration config = Configuration.getInstance();
    private String origSyncWrites;

    @BeforeEach
    public void setUp() {
        origSyncWrites = config.stringValue(ConfigKey.SYNC_WRITES);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "syncWrites", origSyncWrites);
    }

    @Test
    public void test_a_platform_that_cannot_sync_a_folder_falls_back_to_unsynced_writes() throws Exception {
        TestUtils.setPrivateField(config, "syncWrites", "true");
        final var fs = mock(FileSystem.class);
        doThrow(new IOException("directories cannot be opened")).when(fs).verifyDeviceSync();
        try (var logWriter = mockStatic(LogWriter.class)) {
            assertDoesNotThrow(() -> DeviceSyncGuard.fallBackWhenUnsyncable(fs));
            logWriter.verify(() -> LogWriter.writeLogEntry(argThat(entry -> entry.contains("WARNING")
                    && entry.contains("DURABILITY") && entry.contains("Falling back to syncWrites=false"))));
        }
        assertFalse(config.isSyncWrites());
    }

    @Test
    public void test_a_platform_that_syncs_keeps_synced_writes() throws Exception {
        TestUtils.setPrivateField(config, "syncWrites", "true");
        assertDoesNotThrow(() -> DeviceSyncGuard.fallBackWhenUnsyncable(mock(FileSystem.class)));
        assertTrue(config.isSyncWrites());
    }
}
