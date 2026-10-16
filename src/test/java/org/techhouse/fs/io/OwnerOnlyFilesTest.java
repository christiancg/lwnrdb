package org.techhouse.fs.io;

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
import org.techhouse.fs.io.OwnerOnlyFiles.Creation;

public class OwnerOnlyFilesTest {
    private static final String OWNER_DIRECTORY = "rwx------";
    private static final String OWNER_FILE = "rw-------";

    @TempDir
    Path tmp;

    @BeforeEach
    public void requirePosix() {
        assumeTrue(OwnerOnlyFiles.supported());
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

        assertEquals(Creation.OWNER_ONLY, OwnerOnlyFiles.createDirectories(deepest));

        assertEquals(OWNER_DIRECTORY, modeOf(tmp.resolve("a")));
        assertEquals(OWNER_DIRECTORY, modeOf(tmp.resolve("a").resolve("b")));
        assertEquals(OWNER_DIRECTORY, modeOf(deepest));
    }

    @Test
    public void test_create_directory_makes_one_owner_only_level_like_mkdir() throws IOException {
        final var level = tmp.resolve("single");
        assertTrue(OwnerOnlyFiles.createDirectory(level));
        assertEquals(OWNER_DIRECTORY, modeOf(level));
        assertFalse(OwnerOnlyFiles.createDirectory(level));
        assertFalse(OwnerOnlyFiles.createDirectory(tmp.resolve("missing").resolve("child")));
        assertTrue(OwnerOnlyFiles.createDirectory(tmp.resolve("plain"), false));
        assertTrue(Files.isDirectory(tmp.resolve("plain")));
    }

    @Test
    public void test_existing_directories_keep_their_mode() throws IOException {
        final var parent = withMode(Files.createDirectory(tmp.resolve("shared")), "rwxr-xr-x");

        OwnerOnlyFiles.createDirectories(parent.resolve("child"));

        assertEquals("rwxr-xr-x", modeOf(parent));
        assertEquals(OWNER_DIRECTORY, modeOf(parent.resolve("child")));
    }

    @Test
    public void test_an_existing_directory_is_left_alone() throws IOException {
        final var existing = withMode(Files.createDirectory(tmp.resolve("there")), "rwxrwxr-x");

        OwnerOnlyFiles.createDirectories(existing);

        assertEquals("rwxrwxr-x", modeOf(existing));
    }

    @Test
    public void test_a_level_that_is_a_file_fails_like_the_jdk_call() throws IOException {
        final var file = Files.writeString(tmp.resolve("plain"), "x");

        assertThrows(IOException.class, () -> OwnerOnlyFiles.createDirectories(file.resolve("child")));
    }

    @Test
    public void test_a_dangling_symlink_in_the_way_fails_like_the_jdk_call() throws IOException {
        final var link = Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("nowhere"));

        assertThrows(IOException.class, () -> OwnerOnlyFiles.createDirectories(link));
    }

    @Test
    public void test_created_file_is_owner_only_and_writable() throws IOException {
        final var file = tmp.resolve("lwnrdb.p12");

        try (var out = OwnerOnlyFiles.newOwnerOnlyOutputStream(file)) {
            out.write("secret".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(OWNER_FILE, modeOf(file));
        assertEquals("secret", Files.readString(file));
    }

    @Test
    public void test_existing_file_keeps_its_mode() throws IOException {
        final var file = withMode(Files.writeString(tmp.resolve("existing.p12"), "old content"), "rw-r--r--");

        try (var out = OwnerOnlyFiles.newOwnerOnlyOutputStream(file)) {
            out.write(new byte[]{1});
        }

        assertEquals("rw-r--r--", modeOf(file));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(file));
    }

    @Test
    public void test_a_file_in_a_missing_folder_fails_like_the_jdk_call() {
        final var file = tmp.resolve("missing").resolve("lwnrdb.p12");

        assertThrows(IOException.class, () -> OwnerOnlyFiles.newOwnerOnlyOutputStream(file).close());
    }

    @Test
    public void test_isAccessibleByOthers_detects_group_read() throws IOException {
        assertTrue(OwnerOnlyFiles.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("g")), "rw-r-----")));
    }

    @Test
    public void test_isAccessibleByOthers_detects_world_write() throws IOException {
        assertTrue(OwnerOnlyFiles.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("w")), "rw-----w-")));
    }

    @Test
    public void test_isAccessibleByOthers_false_for_owner_only() throws IOException {
        assertFalse(OwnerOnlyFiles.isAccessibleByOthers(withMode(Files.createFile(tmp.resolve("o")), OWNER_FILE)));
    }

    @Test
    public void test_isAccessibleByOthers_false_for_missing_path() {
        assertFalse(OwnerOnlyFiles.isAccessibleByOthers(tmp.resolve("absent")));
    }

    @Test
    public void test_unsupported_filesystem_falls_back() throws IOException {
        final var deepest = tmp.resolve("x").resolve("y");
        assertEquals(Creation.NOT_SUPPORTED, OwnerOnlyFiles.createDirectories(deepest, false));
        assertTrue(Files.isDirectory(deepest));
        assertEquals(Creation.NOT_SUPPORTED, OwnerOnlyFiles.ensureOwnerOnlyFile(deepest.resolve("never"), false));
        assertFalse(Files.exists(deepest.resolve("never")));

        final var file = deepest.resolve("plain.p12");
        try (var out = OwnerOnlyFiles.newOwnerOnlyOutputStream(file, false)) {
            out.write(new byte[]{2});
        }
        assertTrue(Files.exists(file));

        assertFalse(OwnerOnlyFiles.isAccessibleByOthers(withMode(file, "rwxrwxrwx"), false));
    }

    @Test
    public void ensure_owner_only_file_creates_a_missing_file_as_0600_and_leaves_an_existing_one() throws IOException {
        final var file = tmp.resolve("ensured.dat");
        assertEquals(Creation.OWNER_ONLY, OwnerOnlyFiles.ensureOwnerOnlyFile(file));
        assertEquals(OWNER_FILE, modeOf(file));
        Files.writeString(file, "kept");
        assertEquals(Creation.ALREADY_EXISTED, OwnerOnlyFiles.ensureOwnerOnlyFile(file));
        assertEquals("kept", Files.readString(file));
    }

    @Test
    public void create_new_owner_only_file_reports_whether_it_created_the_file() throws IOException {
        final var file = tmp.resolve("created.dat");
        assertEquals(Creation.OWNER_ONLY, OwnerOnlyFiles.createNewOwnerOnlyFile(file));
        assertEquals(Creation.ALREADY_EXISTED, OwnerOnlyFiles.createNewOwnerOnlyFile(file));
        assertEquals(OWNER_FILE, modeOf(file));
    }

    @Test
    public void a_file_that_cannot_be_created_owner_only_reports_default_permissions() {
        final var unreachable = tmp.resolve("missing-parent").resolve("quiet.log");
        assertEquals(Creation.DEFAULT_PERMISSIONS, OwnerOnlyFiles.ensureOwnerOnlyFile(unreachable));
        assertFalse(Files.exists(unreachable));
    }

    @Test
    public void create_new_owner_only_file_in_a_missing_folder_fails_like_the_jdk_call() {
        assertThrows(IOException.class,
                () -> OwnerOnlyFiles.createNewOwnerOnlyFile(tmp.resolve("missing").resolve("x.dat")));
    }
}
