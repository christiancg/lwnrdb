package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileTreeDeleterTest {

    private static File write(File folder, String name) throws IOException {
        final var file = new File(folder, name);
        Files.writeString(file.toPath(), "x");
        return file;
    }

    private static File folder(File parent, String name) {
        final var created = new File(parent, name);
        assertTrue(created.mkdir());
        return created;
    }

    @Test
    public void test_deletes_a_whole_tree(@TempDir File tmp) throws IOException {
        final var db = folder(tmp, "db");
        final var coll = folder(db, "coll");
        write(coll, "coll-0.dat");
        write(coll, "coll-pk.idx");

        assertTrue(FileTreeDeleter.delete(db));
        assertFalse(db.exists());
    }

    @Test
    public void test_deletes_nested_folders(@TempDir File tmp) throws IOException {
        final var db = folder(tmp, "db");
        final var procedures = folder(db, ".procedures");
        write(procedures, "proc.json");
        write(folder(procedures, "nested"), "deep.json");

        assertTrue(FileTreeDeleter.delete(db));
        assertFalse(db.exists());
    }

    @Test
    public void test_a_child_removed_after_it_was_listed_counts_as_deleted(@TempDir File tmp) {
        final var coll = folder(tmp, "coll");
        final var staleListing = new File(coll.getPath()) {
            @Override
            public File[] listFiles() {
                return new File[]{new File(this, "coll-indexes.dirty")};
            }
        };

        assertTrue(FileTreeDeleter.delete(staleListing));
        assertFalse(coll.exists());
    }

    @Test
    public void test_an_entry_written_during_the_first_sweep_is_removed_by_the_second(@TempDir File tmp)
            throws IOException {
        final var coll = folder(tmp, "coll");
        write(coll, "coll-0.dat");
        final var written = new AtomicBoolean();
        final var writesOnce = new File(coll.getPath()) {
            @Override
            public File[] listFiles() {
                final var children = super.listFiles();
                if (written.compareAndSet(false, true)) {
                    try {
                        Files.writeString(new File(this, "coll-indexes.dirty").toPath(), "1");
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return children;
            }
        };

        assertTrue(FileTreeDeleter.delete(writesOnce));
        assertFalse(coll.exists());
    }

    @Test
    public void test_a_folder_that_never_empties_is_reported_as_not_deleted(@TempDir File tmp) {
        final var coll = folder(tmp, "coll");
        final var writes = new AtomicInteger();
        final var alwaysWrites = new File(coll.getPath()) {
            @Override
            public File[] listFiles() {
                final var children = super.listFiles();
                try {
                    Files.writeString(new File(this, "coll-" + writes.incrementAndGet() + ".dirty").toPath(), "1");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return children;
            }
        };

        assertFalse(FileTreeDeleter.delete(alwaysWrites));
        assertTrue(coll.exists());
    }

    @Test
    public void test_deletes_a_single_file(@TempDir File tmp) throws IOException {
        final var file = write(tmp, "lonely.dat");

        assertTrue(FileTreeDeleter.delete(file));
        assertFalse(file.exists());
    }

    @Test
    public void test_a_missing_target_counts_as_deleted(@TempDir File tmp) {
        assertTrue(FileTreeDeleter.delete(new File(tmp, "never-existed")));
    }
}
