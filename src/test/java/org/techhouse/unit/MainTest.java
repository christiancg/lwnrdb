package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.techhouse.Main;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.config.Configuration;
import org.techhouse.ex.InvalidPortException;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MainTest {
    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        // Stop them here, or a leaked worker writing admin/collection_usage corrupts later tests in the
        // same JVM fork.
        IocContainer.get(BackgroundTaskManager.class).stopBackgroundWorkers();
        IocContainer.get(MemoryManagement.class).stopSweepThread();
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

    @Test
    public void test_init_with_default_port()
            throws NoSuchFieldException, IllegalAccessException, InterruptedException {
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

    @Test
    public void test_init_with_tls_enabled() throws NoSuchFieldException, IllegalAccessException, InterruptedException {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        TestUtils.setPrivateField(config, "port", 9092);
        TestUtils.setPrivateField(config, "tlsEnabled", true);
        TestUtils.setPrivateField(config, "tlsKeystorePath", TestGlobals.PATH + "/lwnrdb.p12");
        TestUtils.setPrivateField(config, "tlsKeystorePassword", "change_it");
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
            assertTrue(new File(TestGlobals.PATH + "/lwnrdb.p12").exists());
        } finally {
            if (thread != null) {
                thread.interrupt();
            }
            TestUtils.setPrivateField(config, "tlsEnabled", false);
        }
    }

    @Test
    public void test_invalid_port_throws_exception() {
        String[] args = new String[]{"invalid_port"};

        assertThrows(InvalidPortException.class, () -> Main.main(args));
    }

    @Test
    public void test_valid_port_arg_starts_server() throws Exception {
        Configuration config = Configuration.getInstance();
        TestUtils.setPrivateField(config, "filePath", TestGlobals.PATH);
        TestUtils.setPrivateField(config, "logPath", TestGlobals.LOG_PATH);
        String[] args = new String[]{"19099"};
        Thread thread = new Thread(() -> {
            try {
                Main.main(args);
            } catch (Exception ignored) {
            }
        });
        thread.start();
        Thread.sleep(500);
        thread.interrupt();
        thread.join(2000);
    }

}
