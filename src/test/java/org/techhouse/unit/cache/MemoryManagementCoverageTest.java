package org.techhouse.unit.cache;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MemoryManagementCoverageTest {
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();
    private long realMaxMemory;
    private FileSystem realFs;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realFs = TestUtils.getPrivateField(memoryManagement, "fs", FileSystem.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 100L * 1024 * 1024);
    }

    @AfterEach
    public void tearDown() throws Exception {
        memoryManagement.stopSweepThread();
        TestUtils.setPrivateField(memoryManagement, "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", realMaxMemory);
        TestUtils.standardTearDown();
    }

    @Test
    public void test_start_and_stop_sweep_thread() {
        memoryManagement.startSweepThread();
        memoryManagement.stopSweepThread();
    }

    @Test
    public void test_load_profile_swallows_stream_failure() throws Exception {
        final var throwingFs = mock(FileSystem.class, invocation -> {
            throw new RuntimeException("boom");
        });
        TestUtils.setPrivateField(memoryManagement, "fs", throwingFs);
        memoryManagement.loadProfileFromAdmin();
    }
}
