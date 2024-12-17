package org.techhouse.unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.Main;
import org.techhouse.config.Configuration;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MainTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        final var dbPath = new File(TestGlobals.PATH);
        if (dbPath.exists()) {
            TestUtils.deleteFolder(dbPath);
        }
        final var logPath = new File(TestGlobals.LOG_PATH);
        if (logPath.exists()) {
            TestUtils.deleteFolder(logPath);
        }
        TestUtils.releaseAllLocks();
    }

    // Main initializes system with default port from Configuration when no args provided
    @Test
    public void test_init_with_default_port() throws NoSuchFieldException, IllegalAccessException, InterruptedException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        TestUtils.setPrivateField(config, "port", 9090);
        String[] args = new String[]{};
        Thread thread = null;
        try {
            thread = new Thread(() -> {
                    try {
                        Main.main(args);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            thread.start();
            Thread.sleep(1000);
            assertTrue(thread.isAlive());
        } finally {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    // Invalid port number provided as command line argument
    @Test
    public void test_invalid_port_throws_exception() {
        String[] args = new String[]{"invalid_port"};
    
        assertThrows(InvalidPortException.class, () -> Main.main(args));
    }
}