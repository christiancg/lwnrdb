package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.StorageFormatGuard;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.config.ServerVersion;
import org.techhouse.ex.IncompatibleStorageFormatException;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.FormatStamp;
import org.techhouse.fs.FormatStampFile;
import org.techhouse.fs.MalformedFormatStampException;
import org.techhouse.test.TestUtils;

public class StorageFormatGuardTest {
    private final Configuration config = Configuration.getInstance();
    private String origFilePath;
    private FileSystem fs;

    @TempDir
    Path root;

    @BeforeEach
    public void setUp() throws Exception {
        origFilePath = config.getFilePath();
        TestUtils.setPrivateField(config, "filePath", root.toString());
        fs = new FileSystem();
        fs.createBaseDbPath();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "filePath", origFilePath);
    }

    private Path stampPath() {
        return fs.formatStamp().path();
    }

    private void stampWith(int formatVersion, String writtenBy) throws IOException {
        fs.formatStamp().write(new FormatStamp(formatVersion, writtenBy));
    }

    private List<Path> tree() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.sorted().toList();
        }
    }

    private String refusal() {
        return assertThrows(IncompatibleStorageFormatException.class,
                () -> StorageFormatGuard.refuseIncompatibleFolder(fs)).getMessage();
    }

    @Test
    public void test_a_fresh_folder_passes_and_is_stamped_with_the_current_format() throws IOException {
        assertDoesNotThrow(() -> StorageFormatGuard.refuseIncompatibleFolder(fs));
        assertFalse(Files.exists(stampPath()));

        StorageFormatGuard.stampIfUnstamped(fs);

        assertEquals(new FormatStamp(Globals.STORAGE_FORMAT_VERSION, ServerVersion.current()), fs.formatStamp().read());
    }

    @Test
    public void test_an_unstamped_folder_holding_data_is_read_as_the_unstamped_format_and_stamped() throws IOException {
        Files.createDirectories(root.resolve(Globals.ADMIN_DB_NAME));

        assertEquals(Globals.STORAGE_FORMAT_VERSION, Globals.UNSTAMPED_STORAGE_FORMAT_VERSION);
        assertDoesNotThrow(() -> StorageFormatGuard.refuseIncompatibleFolder(fs));
        StorageFormatGuard.stampIfUnstamped(fs);

        assertEquals(new FormatStamp(Globals.UNSTAMPED_STORAGE_FORMAT_VERSION, ServerVersion.current()),
                fs.formatStamp().read());
    }

    @Test
    public void test_a_matching_stamp_passes_and_is_never_rewritten() throws IOException {
        stampWith(Globals.STORAGE_FORMAT_VERSION, "0.0.1-original");
        Files.createDirectories(root.resolve(Globals.ADMIN_DB_NAME));
        final var before = Files.readAllBytes(stampPath());

        assertDoesNotThrow(() -> StorageFormatGuard.refuseIncompatibleFolder(fs));
        StorageFormatGuard.stampIfUnstamped(fs);

        assertArrayEquals(before, Files.readAllBytes(stampPath()));
        assertEquals(new FormatStamp(Globals.STORAGE_FORMAT_VERSION, "0.0.1-original"), fs.formatStamp().read());
    }

    @Test
    public void test_a_newer_format_is_refused_naming_both_versions_and_the_writer() throws IOException {
        stampWith(Globals.STORAGE_FORMAT_VERSION + 1, "9.9.9");

        final var message = refusal();

        assertTrue(message.startsWith(stampPath().toAbsolutePath().toString()), message);
        assertTrue(message.contains("storage format " + (Globals.STORAGE_FORMAT_VERSION + 1)), message);
        assertTrue(message.contains("written by lwnrdb 9.9.9"), message);
        assertTrue(message.contains("reads only format " + Globals.STORAGE_FORMAT_VERSION), message);
        assertTrue(message.contains("Run lwnrdb 9.9.9 or newer"), message);
    }

    @Test
    public void test_an_older_format_is_refused_with_the_dump_and_restore_steps() throws IOException {
        final var olderFs = mock(FileSystem.class);
        final var file = mock(FormatStampFile.class);
        when(olderFs.formatStamp()).thenReturn(file);
        when(file.path()).thenReturn(stampPath());
        when(file.read()).thenReturn(new FormatStamp(Globals.STORAGE_FORMAT_VERSION - 1, "0.0.9"));

        final var message = assertThrows(IncompatibleStorageFormatException.class,
                () -> StorageFormatGuard.refuseIncompatibleFolder(olderFs)).getMessage();

        assertTrue(message.contains("written by lwnrdb 0.0.9"), message);
        assertTrue(message.contains("Dump it with lwnrdb 0.0.9"), message);
        assertTrue(message.contains("--dump"), message);
        assertTrue(message.contains("--restore"), message);
    }

    @Test
    public void test_a_zero_format_version_is_refused_as_malformed() throws IOException {
        Files.writeString(stampPath(), "{\"formatVersion\":0,\"writtenBy\":\"0.0.9\"}", StandardCharsets.UTF_8);

        final var refused = assertThrows(IncompatibleStorageFormatException.class,
                () -> StorageFormatGuard.refuseIncompatibleFolder(fs));

        assertInstanceOf(MalformedFormatStampException.class, refused.getCause());
    }

    @Test
    public void test_a_malformed_stamp_is_refused_naming_the_file() throws IOException {
        Files.writeString(stampPath(), "not json", StandardCharsets.UTF_8);

        final var message = refusal();

        assertTrue(message.startsWith(stampPath().toAbsolutePath() + " is not a valid storage format stamp"), message);
    }

    @Test
    public void test_an_unreadable_stamp_is_refused_not_treated_as_fresh() throws IOException {
        Files.createDirectories(stampPath());

        final var message = refusal();

        assertTrue(message.startsWith("Could not read the storage format stamp at "), message);
        assertTrue(Files.isDirectory(stampPath()));
    }

    @Test
    public void test_a_refused_folder_is_left_untouched() throws IOException {
        stampWith(Globals.STORAGE_FORMAT_VERSION + 1, "9.9.9");
        Files.createDirectories(root.resolve(Globals.SNAPSHOT_FOLDER_NAME).resolve("s1"));
        final var before = tree();
        final var stampBytes = Files.readAllBytes(stampPath());

        refusal();

        assertEquals(before, tree());
        assertArrayEquals(stampBytes, Files.readAllBytes(stampPath()));
        assertFalse(Files.exists(root.resolve(Globals.ADMIN_DB_NAME)));
    }
}
