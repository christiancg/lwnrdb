package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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

    @Test
    public void test_deleting_an_absent_file_answers_false(@TempDir File tmp) throws IOException {
        assertFalse(MetadataFileStore.delete(new File(tmp, "absent.json")));
    }

    @Test
    public void test_deleting_a_present_file_answers_true(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "present.json");
        Files.writeString(file.toPath(), "{}", StandardCharsets.UTF_8);

        assertTrue(MetadataFileStore.delete(file));
        assertFalse(file.exists());
    }

    @Test
    public void test_a_delete_that_fails_throws_instead_of_answering_absent(@TempDir File tmp) throws IOException {
        final var folder = new File(tmp, "locked");
        assertTrue(folder.mkdir());
        final var file = new File(folder, "kept.json");
        Files.writeString(file.toPath(), "{}", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assumeFalse(Files.isWritable(folder.toPath()), "a superuser ignores the permission bits");

            assertThrows(IOException.class, () -> MetadataFileStore.delete(file));
            assertTrue(file.exists());
        } finally {
            Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    public void test_listing_an_absent_folder_answers_empty(@TempDir File tmp) throws IOException {
        assertTrue(MetadataFileStore.listNames(new File(tmp, "absent"), ".json").isEmpty());
    }

    @Test
    public void test_listing_a_folder_that_cannot_be_read_throws(@TempDir File tmp) throws IOException {
        final var notAFolder = new File(tmp, "file");
        Files.writeString(notAFolder.toPath(), "x", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> MetadataFileStore.listNames(notAFolder, ".json"));
    }

    @Test
    public void test_listing_answers_the_sorted_names_with_the_extension(@TempDir File tmp) throws IOException {
        Files.writeString(new File(tmp, "b.json").toPath(), "{}", StandardCharsets.UTF_8);
        Files.writeString(new File(tmp, "a.json").toPath(), "{}", StandardCharsets.UTF_8);
        Files.writeString(new File(tmp, "c.txt").toPath(), "{}", StandardCharsets.UTF_8);

        assertEquals(List.of("a", "b"), MetadataFileStore.listNames(tmp, ".json"));
    }

    @Test
    public void test_a_file_in_a_missing_folder_is_not_listed(@TempDir File tmp) throws IOException {
        assertFalse(MetadataFileStore.isListedExactly(new File(new File(tmp, "absent"), "one.json")));
    }

    @Test
    public void test_only_the_exact_spelling_is_listed(@TempDir File tmp) throws IOException {
        Files.writeString(new File(tmp, "foo.json").toPath(), "{}", StandardCharsets.UTF_8);

        assertTrue(MetadataFileStore.isListedExactly(new File(tmp, "foo.json")));
        assertFalse(MetadataFileStore.isListedExactly(new File(tmp, "Foo.json")));
    }

    @Test
    public void test_a_folder_that_cannot_be_listed_throws_instead_of_answering_absent(@TempDir File tmp)
            throws IOException {
        final var notAFolder = new File(tmp, "file");
        Files.writeString(notAFolder.toPath(), "x", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> MetadataFileStore.isListedExactly(new File(notAFolder, "one.json")));
    }
}
