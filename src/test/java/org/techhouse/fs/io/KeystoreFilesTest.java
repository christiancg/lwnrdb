package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.fs.io.OwnerOnlyFiles.Creation;

public class KeystoreFilesTest {
    private static final byte[] CONTENT = {1, 2, 3, 4};

    @TempDir
    Path tmp;

    private static String modeOf(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    @Test
    public void write_creates_the_parent_and_the_file_owner_only() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        final var keystore = tmp.resolve("certs").resolve("lwnrdb.p12");

        assertEquals(Creation.OWNER_ONLY, KeystoreFiles.writeOwnerOnly(keystore, CONTENT));

        assertEquals("rwx------", modeOf(keystore.getParent()));
        assertEquals("rw-------", modeOf(keystore));
    }

    @Test
    public void read_returns_the_written_bytes() throws IOException {
        final var keystore = tmp.resolve("lwnrdb.p12");
        KeystoreFiles.writeOwnerOnly(keystore, CONTENT);

        assertTrue(KeystoreFiles.exists(keystore));
        assertArrayEquals(CONTENT, KeystoreFiles.read(keystore));
    }

    @Test
    public void rewriting_an_existing_keystore_replaces_its_content() throws IOException {
        final var keystore = Files.write(tmp.resolve("lwnrdb.p12"), new byte[]{9, 9, 9, 9, 9, 9});

        KeystoreFiles.writeOwnerOnly(keystore, CONTENT);

        assertArrayEquals(CONTENT, KeystoreFiles.read(keystore));
    }

    @Test
    public void exists_is_false_for_a_missing_file() {
        assertFalse(KeystoreFiles.exists(tmp.resolve("absent.p12")));
    }
}
