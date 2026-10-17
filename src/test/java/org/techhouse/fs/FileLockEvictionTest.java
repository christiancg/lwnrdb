package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FileLockEvictionTest {

    @Test
    public void test_deleting_a_file_evicts_its_lock_entry(@TempDir File tmp) throws IOException {
        final var file = new File(tmp, "procedure.json");
        MetadataFileStore.write(file, "{}");
        final var before = FileLocks.lockFor(file);

        assertTrue(MetadataFileStore.delete(file));

        assertNotSame(before, FileLocks.lockFor(file), "a deleted file must not keep its lock registered forever");
    }

    @Test
    public void test_a_held_lock_is_not_evicted(@TempDir File tmp) {
        final var file = new File(tmp, "held.json");
        final var held = FileLocks.lockWrite(file);
        try {
            final var registered = FileLocks.lockFor(file);
            FileLocks.evictIfUnused(file);
            assertSame(registered, FileLocks.lockFor(file), "evicting a held lock would strand its holder");
        } finally {
            held.unlock();
        }
    }

    @Test
    public void test_an_acquisition_holds_the_instance_that_is_mapped(@TempDir File tmp) {
        final var file = new File(tmp, "page-0.dat");
        final var evicted = FileLocks.lockFor(file);
        FileLocks.evictIfUnused(file);

        final var held = FileLocks.lockWrite(file);
        try {
            assertTrue(FileLocks.lockFor(file).isWriteLockedByCurrentThread());
            assertFalse(evicted.isWriteLocked(), "an evicted instance is never the one a writer holds");
        } finally {
            held.unlock();
        }
    }

    @Test
    public void test_deleting_a_folder_evicts_every_lock_under_it_and_nothing_beside_it(@TempDir File tmp) {
        final var folder = new File(tmp, "coll");
        final var sibling = new File(tmp, "coll2");
        final var page = new File(folder, "coll-0.dat");
        final var index = new File(folder, "coll-pk.idx");
        final var neighbour = new File(sibling, "coll2-0.dat");
        final var pageLock = FileLocks.lockFor(page);
        final var indexLock = FileLocks.lockFor(index);
        final var neighbourLock = FileLocks.lockFor(neighbour);

        FileLocks.evictAllUnder(folder);

        assertNotSame(pageLock, FileLocks.lockFor(page));
        assertNotSame(indexLock, FileLocks.lockFor(index));
        assertSame(neighbourLock, FileLocks.lockFor(neighbour), "a folder sharing the name prefix is not under it");
    }
}
