package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MetadataFileStoreTest {

    @Test
    public void test_a_missing_file_reads_as_absent(@TempDir File tmp) throws IOException {
        assertNull(MetadataFileStore.read(new File(tmp, "absent.json")));
    }

    @Test
    public void test_an_existing_file_reads_its_content(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "present.json");
        Files.writeString(file.toPath(), "{\"a\":1}", StandardCharsets.UTF_8);

        assertEquals("{\"a\":1}", MetadataFileStore.read(file));
    }

    @Test
    public void test_a_file_deleted_while_the_read_waits_reads_as_absent(@TempDir File tmp) throws Exception {
        final var file = new File(tmp, "racing.json");
        Files.writeString(file.toPath(), "{\"a\":1}", StandardCharsets.UTF_8);
        final var lock = FileLocks.lockFor(file).writeLock();
        lock.lock();
        final CompletableFuture<String> read;
        try {
            read = CompletableFuture.supplyAsync(() -> {
                try {
                    return MetadataFileStore.read(file);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            final var deadline = System.currentTimeMillis() + 5000;
            while (!FileLocks.lockFor(file).hasQueuedThreads() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(file.delete());
        } finally {
            lock.unlock();
        }

        assertNull(read.get(5, TimeUnit.SECONDS),
                "a delete that lands between the existence check and the read is an absence, not a read failure");
    }
}
