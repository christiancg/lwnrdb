package org.techhouse.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class OwnerOnlyPermissionsTest {
    private static final String OWNER_DIRECTORY = "rwx------";
    private static final String OWNER_FILE = "rw-------";

    @TempDir
    Path tmp;

    @BeforeEach
    public void requirePosix() {
        assumeTrue(OwnerOnlyPermissions.supported());
    }

    private static String modeOf(Path path) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    }

    private static Path withMode(Path path, String mode) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
        return path;
    }

    @Test
    public void test_created_directories_are_owner_only() throws IOException {
        final var deepest = tmp.resolve("a").resolve("b").resolve("c");

        OwnerOnlyPermissions.createDirectories(deepest);

        assertEquals(OWNER_DIRECTORY, modeOf(tmp.resolve("a")));
        assertEquals(OWNER_DIRECTORY, modeOf(tmp.resolve("a").resolve("b")));
        assertEquals(OWNER_DIRECTORY, modeOf(deepest));
    }

    @Test
    public void test_existing_directories_keep_their_mode() throws IOException {
        final var parent = withMode(Files.createDirectory(tmp.resolve("shared")), "rwxr-xr-x");

        OwnerOnlyPermissions.createDirectories(parent.resolve("child"));

        assertEquals("rwxr-xr-x", modeOf(parent));
        assertEquals(OWNER_DIRECTORY, modeOf(parent.resolve("child")));
    }

    @Test
    public void test_an_existing_directory_is_left_alone() throws IOException {
        final var existing = withMode(Files.createDirectory(tmp.resolve("there")), "rwxrwxr-x");

        OwnerOnlyPermissions.createDirectories(existing);

        assertEquals("rwxrwxr-x", modeOf(existing));
    }

    @Test
    public void test_a_level_that_is_a_file_fails_like_the_jdk_call() throws IOException {
        final var file = Files.writeString(tmp.resolve("plain"), "x");

        assertThrows(IOException.class, () -> OwnerOnlyPermissions.createDirectories(file.resolve("child")));
    }

    @Test
    public void test_a_dangling_symlink_in_the_way_fails_like_the_jdk_call() throws IOException {
        final var link = Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("nowhere"));

        assertThrows(IOException.class, () -> OwnerOnlyPermissions.createDirectories(link));
    }

    @Test
    public void test_created_file_is_owner_only_and_writable() throws IOException {
        final var file = tmp.resolve("lwnrdb.p12");

        try (var out = OwnerOnlyPermissions.newOwnerOnlyOutputStream(file)) {
            out.write("secret".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(OWNER_FILE, modeOf(file));
        assertEquals("secret", Files.readString(file));
    }

    @Test
    public void test_existing_file_keeps_its_mode() throws IOException {
        final var file = withMode(Files.writeString(tmp.resolve("existing.p12"), "old content"), "rw-r--r--");

        try (var out = OwnerOnlyPermissions.newOwnerOnlyOutputStream(file)) {
            out.write(new byte[]{1});
        }

        assertEquals("rw-r--r--", modeOf(file));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(file));
    }

    @Test
    public void test_a_file_in_a_missing_folder_fails_like_the_jdk_call() {
        final var file = tmp.resolve("missing").resolve("lwnrdb.p12");

        assertThrows(IOException.class, () -> OwnerOnlyPermissions.newOwnerOnlyOutputStream(file).close());
    }

    @Test
    public void test_isAccessibleByOthers_detects_group_read() throws IOException {
        assertTrue(
                OwnerOnlyPermissions.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("g")), "rw-r-----")));
    }

    @Test
    public void test_isAccessibleByOthers_detects_world_write() throws IOException {
        assertTrue(
                OwnerOnlyPermissions.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("w")), "rw-----w-")));
    }

    @Test
    public void test_isAccessibleByOthers_false_for_owner_only() throws IOException {
        assertFalse(
                OwnerOnlyPermissions.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("o")), OWNER_FILE)));
    }

    @Test
    public void test_isAccessibleByOthers_false_for_missing_path() {
        assertFalse(OwnerOnlyPermissions.isAccessibleByOthers(tmp.resolve("absent")));
    }

    @Test
    public void test_unsupported_filesystem_falls_back() throws IOException {
        final var deepest = tmp.resolve("x").resolve("y");
        OwnerOnlyPermissions.createDirectories(deepest, false);
        assertTrue(Files.isDirectory(deepest));

        final var file = deepest.resolve("plain.p12");
        try (var out = OwnerOnlyPermissions.newOwnerOnlyOutputStream(file, false)) {
            out.write(new byte[]{2});
        }
        assertTrue(Files.exists(file));

        assertFalse(OwnerOnlyPermissions.isAccessibleByOthers(withMode(file, "rwxrwxrwx"), false));
    }
}
