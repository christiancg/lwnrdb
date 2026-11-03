package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SyncedWritesTest {
    @TempDir
    File tmp;

    private RecordingForcer recorder;

    @AfterEach
    public void tearDown() throws Exception {
        if (recorder != null) {
            recorder.close();
        }
    }

    private File file() throws IOException {
        final var file = new File(tmp, "page-0.dat");
        Files.writeString(file.toPath(), "x");
        return file;
    }

    @Test
    public void test_off_forces_nothing() throws Exception {
        recorder = RecordingForcer.install(false);
        final var file = file();

        try (var out = new FileOutputStream(file, true)) {
            SyncedWrites.content(out.getChannel(), file.toPath());
        }
        SyncedWrites.content(file.toPath());
        SyncedWrites.entries(tmp.toPath());
        SyncedWrites.verifyFolderSync(tmp.toPath());

        assertEquals(List.of(), recorder.forced());
    }

    @Test
    public void test_on_forces_the_channel_and_the_path() throws Exception {
        recorder = RecordingForcer.install(true);
        final var file = file();

        try (var out = new FileOutputStream(file, true)) {
            SyncedWrites.content(out.getChannel(), file.toPath());
        }
        SyncedWrites.content(file.toPath());
        SyncedWrites.entries(tmp.toPath());

        final var expectedFile = file.toPath().toAbsolutePath().normalize();
        assertEquals(List.of(expectedFile, expectedFile, tmp.toPath().toAbsolutePath().normalize()), recorder.forced());
    }

    @Test
    public void test_entries_forces_a_real_folder() throws Exception {
        recorder = RecordingForcer.install(true);
        recorder.close();
        recorder = null;

        assertDoesNotThrow(() -> SyncedWrites.entries(tmp.toPath()));
        assertDoesNotThrow(() -> SyncedWrites.content(file().toPath()));
    }

    @Test
    public void test_force_failure_propagates() throws Exception {
        recorder = RecordingForcer.install(true).failingFiles();
        final var file = file();

        final var thrown = assertThrows(IOException.class, () -> {
            try (var out = new FileOutputStream(file, true)) {
                out.write('y');
                SyncedWrites.content(out.getChannel(), file.toPath());
            }
        });
        assertTrue(thrown.getMessage().contains(file.getName()));
    }

    @Test
    public void test_verify_folder_sync_reports_an_unsupported_platform() throws Exception {
        recorder = RecordingForcer.install(true).failingFolders();

        assertThrows(IOException.class, () -> SyncedWrites.verifyFolderSync(tmp.toPath()));
    }

    @Test
    public void test_a_grouped_file_removed_before_its_force_is_skipped() throws Exception {
        recorder = RecordingForcer.install(true);
        final var kept = file();
        final var removed = new File(tmp, "coll-tag-String.idxlog");
        final var group = new ForceGroup();
        group.add(removed.toPath());
        group.add(kept.toPath());

        group.force();

        assertEquals(List.of(kept.toPath().toAbsolutePath().normalize()), recorder.forced(),
                "a fold that deleted an index log between the group's listing and its force fails nothing");
    }

    @Test
    public void test_a_grouped_page_or_pk_index_removed_before_its_force_still_fails() throws Exception {
        recorder = RecordingForcer.install(true);
        final var kept = file();
        for (final var name : List.of("coll-1.dat", "coll-pk.idx", "coll-tag-String.idx", "coll.intents")) {
            final var group = new ForceGroup();
            group.add(new File(tmp, name).toPath());
            group.add(kept.toPath());

            assertThrows(NoSuchFileException.class, group::force,
                    "a missing " + name + " is a failure, not an absence");
        }
    }
}
