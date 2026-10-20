package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.techhouse.DeviceSyncGuard;
import org.techhouse.ex.InvalidConfigurationException;
import org.techhouse.fs.FileSystem;
import org.techhouse.log.LogWriter;

public class DeviceSyncGuardTest {

    @Test
    public void test_a_platform_that_cannot_sync_a_folder_is_refused() throws Exception {
        final var fs = mock(FileSystem.class);
        doThrow(new IOException("directories cannot be opened")).when(fs).verifyDeviceSync();
        try (var logWriter = mockStatic(LogWriter.class)) {
            final var refusal = assertThrows(InvalidConfigurationException.class,
                    () -> DeviceSyncGuard.refuseUnsyncablePlatform(fs));
            assertTrue(refusal.getMessage().contains("syncWrites=false"), refusal.getMessage());
            logWriter.verify(() -> LogWriter
                    .writeLogEntry(argThat(entry -> entry.contains("FATAL") && entry.contains("will not start"))));
        }
    }

    @Test
    public void test_a_platform_that_syncs_starts() {
        assertDoesNotThrow(() -> DeviceSyncGuard.refuseUnsyncablePlatform(mock(FileSystem.class)));
    }
}
