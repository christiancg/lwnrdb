package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class SyncedWritesFaultTest {
    private final FaultInjector injector = IocContainer.get(FaultInjector.class);

    @TempDir
    Path tmp;

    private RecordingForcer recorder;
    private DurableShadow shadow;
    private final String previousSyncWrites = String.valueOf(Configuration.getInstance().isSyncWrites());

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setPrivateField(injector, "enabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        if (recorder != null) {
            recorder.close();
        }
        if (shadow != null) {
            shadow.close();
        }
        SyncedWrites.useForcer(Forcer.DEVICE);
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", previousSyncWrites);
    }

    private Path file(String name) throws IOException {
        final var file = tmp.resolve(name);
        Files.writeString(file, "x");
        return file;
    }

    @Test
    public void anArmedFileForcedFailureThrowsOnceForThatName() throws Exception {
        recorder = RecordingForcer.install(true);
        final var page = file("orders-1.dat");
        final var log = file("orders.wal");
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, "orders.wal");

        SyncedWrites.content(page);
        final var failure = assertThrows(IOException.class, () -> SyncedWrites.content(log));
        SyncedWrites.content(log);

        assertTrue(failure.getMessage().contains("injected force failure"), failure.getMessage());
        assertEquals(List.of("orders-1.dat", "orders.wal"), recorder.forcedNames());
    }

    @Test
    public void aFolderSubjectEndsWithASlash() throws Exception {
        recorder = RecordingForcer.install(true);
        final var folder = Files.createDirectories(tmp.resolve("orders"));
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, "orders/");

        assertThrows(IOException.class, () -> SyncedWrites.entries(folder));
        assertDoesNotThrow(() -> SyncedWrites.entries(folder));
        assertEquals(List.of("orders"), recorder.forcedNames());
    }

    @Test
    public void withInjectionDisabledNothingIsAsked() throws Exception {
        recorder = RecordingForcer.install(true);
        TestUtils.setPrivateField(injector, "enabled", false);
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, null);

        SyncedWrites.content(file("orders.wal"));

        assertFalse(injector.awaitReached(FaultPoint.FILE_FORCED, 0));
        assertEquals(List.of("orders.wal"), recorder.forcedNames());
    }

    @Test
    public void aFailedForceFreezesTheShadowedFile() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", "true");
        final var data = Files.createDirectories(tmp.resolve("db"));
        shadow = DurableShadow.install(data, tmp.resolve("shadow"));
        SyncedWrites.useForcer(shadow);
        final var log = data.resolve("orders.wal");
        Files.writeString(log, "durable");
        SyncedWrites.content(log);
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, "orders.wal");
        Files.writeString(log, "lost");

        assertThrows(IOException.class, () -> SyncedWrites.content(log));
        SyncedWrites.content(log);

        assertTrue(shadow.isFrozen(log));
        assertEquals("durable", Files.readString(shadow.blobOf(log)));
    }

    @Test
    public void theFileSystemInstallsTheShadowAsTheForcer() throws Exception {
        final var data = Files.createDirectories(tmp.resolve("db"));
        Files.writeString(data.resolve("present.dat"), "baseline");
        final var fileSystem = new FileSystem();
        TestUtils.getPrivateField(fileSystem, "paths", FilePaths.class).useDbPath(data.toString());

        fileSystem.installDurableShadow(tmp.resolve("shadow"));

        final var installed = TestUtils.getPrivateStaticField(SyncedWrites.class, "forcer", Forcer.class);
        shadow = assertInstanceOf(DurableShadow.class, installed, String.valueOf(installed));
        assertEquals("baseline", Files.readString(shadow.blobOf(data.resolve("present.dat"))));
    }
}
