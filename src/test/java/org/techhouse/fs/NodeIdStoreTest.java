package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.OwnerOnlyFiles;

public class NodeIdStoreTest {
    private static final String NODE_ID = "4f3a21b8-9c0d-4e1f-a2b3-c4d5e6f70819";

    @TempDir
    Path tmp;

    private NodeIdStore store;

    @BeforeEach
    public void setUp() {
        final var paths = new FilePaths();
        paths.useDbPath(tmp.toString());
        store = new NodeIdStore(paths);
    }

    private Path temporarySibling() {
        return store.file().resolveSibling(store.file().getFileName() + ".tmp");
    }

    private static String modeOf(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    public void an_absent_file_reads_null() throws IOException {
        assertNull(store.read());
    }

    @Test
    public void the_file_sits_in_the_cluster_folder_of_the_data_path() {
        assertEquals(tmp.resolve(Globals.CLUSTER_FOLDER).resolve(Globals.CLUSTER_NODE_ID_FILE), store.file());
    }

    @Test
    public void write_is_atomic_and_owner_only() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        store.write(NODE_ID);

        assertEquals(NODE_ID, store.read());
        assertFalse(Files.exists(temporarySibling()));
        assertEquals("rw-------", modeOf(store.file()));
    }

    @Test
    public void a_write_that_cannot_move_atomically_falls_back_to_replace() throws IOException {
        store.write("replaced-" + NODE_ID);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE),
                    eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AtomicMoveNotSupportedException("a", "b", "no atomic move"));
            store.write(NODE_ID);
        }

        assertEquals(NODE_ID, store.read());
        assertFalse(Files.exists(temporarySibling()));
    }

    @Test
    public void the_folder_is_created_0700() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        store.write(NODE_ID);

        assertEquals("rwx------", modeOf(tmp.resolve(Globals.CLUSTER_FOLDER)));
    }
}
