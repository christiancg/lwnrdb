package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.test.TestUtils;

public class PendingForceTest {
    @TempDir
    File tmp;

    @Test
    public void aForcerThatThrowsUncheckedFailsTheAwait() throws Exception {
        final var file = new File(tmp, "deferred.idx");
        Files.writeString(file.toPath(), "x");
        try (var _ = RecordingForcer.install(true)) {
            TestUtils.setPrivateStaticField(SyncedWrites.class, "forcer", (Forcer) (_, _) -> {
                throw new IllegalStateException("the device went away");
            });

            final var failure = assertThrows(IOException.class, () -> PendingForce.start(file.toPath()).await());

            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("the device went away", failure.getCause().getMessage());
        }
    }

    @Test
    public void aForceThatLandsAwaitsCleanly() throws Exception {
        final var file = new File(tmp, "landed.idx");
        Files.writeString(file.toPath(), "x");
        try (var recorder = RecordingForcer.install(true)) {
            PendingForce.start(file.toPath()).await();

            assertEquals(1, recorder.forcedNames().stream().filter("landed.idx"::equals).count());
        }
    }
}
