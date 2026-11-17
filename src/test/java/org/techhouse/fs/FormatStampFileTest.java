package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.techhouse.config.Globals;
import org.techhouse.fs.io.OwnerOnlyFiles;

public class FormatStampFileTest {
    @TempDir
    Path tmp;

    private FormatStampFile file;

    @BeforeEach
    public void setUp() {
        final var paths = new FilePaths();
        paths.useDbPath(tmp.toString());
        file = new FormatStampFile(paths);
    }

    private void stampWith(String content) throws IOException {
        Files.writeString(file.path(), content, StandardCharsets.UTF_8);
    }

    private String malformedReason(String content) throws IOException {
        stampWith(content);
        return assertThrows(MalformedFormatStampException.class, file::read).getMessage();
    }

    @Test
    public void test_the_stamp_sits_at_the_root_of_the_data_path() {
        assertEquals(tmp.resolve(Globals.FORMAT_STAMP_FILE), file.path());
    }

    @Test
    public void test_an_absent_stamp_reads_as_null() throws IOException {
        assertNull(file.read());
    }

    @Test
    public void test_a_written_stamp_reads_back() throws IOException {
        file.write(new FormatStamp(7, "0.4.2"));

        assertEquals(new FormatStamp(7, "0.4.2"), file.read());
        assertEquals("{\"formatVersion\":7,\"writtenBy\":\"0.4.2\"}",
                Files.readString(file.path(), StandardCharsets.UTF_8).strip());
    }

    @Test
    public void test_the_stamp_file_is_owner_only() throws IOException {
        assumeTrue(OwnerOnlyFiles.supported());
        file.write(FormatStamp.current());

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.path())));
    }

    @Test
    public void test_an_extra_key_is_accepted() throws IOException {
        stampWith("{\"formatVersion\":1,\"writtenBy\":\"0.1.0\",\"note\":\"later\"}");

        assertEquals(new FormatStamp(1, "0.1.0"), file.read());
    }

    @Test
    public void test_a_stamp_that_is_not_json_is_malformed() throws IOException {
        final var reason = malformedReason("not json");

        assertTrue(reason.contains(file.path().toAbsolutePath().toString()), reason);
        assertTrue(reason.contains("not a JSON object"), reason);
    }

    @Test
    public void test_an_empty_stamp_is_malformed() throws IOException {
        assertTrue(malformedReason("").contains("not a JSON object"));
    }

    @Test
    public void test_a_stamp_without_a_format_version_is_malformed() throws IOException {
        assertTrue(malformedReason("{\"writtenBy\":\"0.1.0\"}").contains("formatVersion is missing"));
        assertTrue(malformedReason("{\"formatVersion\":\"1\",\"writtenBy\":\"0.1.0\"}")
                .contains("formatVersion is missing"));
    }

    @Test
    public void test_a_fractional_zero_or_negative_format_version_is_malformed() throws IOException {
        for (final var version : new String[]{"1.5", "0", "-3", "3000000000"}) {
            final var reason = malformedReason("{\"formatVersion\":" + version + ",\"writtenBy\":\"0.1.0\"}");

            assertTrue(reason.contains("must be a positive whole number"), reason);
        }
    }

    @Test
    public void test_a_stamp_without_a_writer_is_malformed() throws IOException {
        assertTrue(malformedReason("{\"formatVersion\":1}").contains("writtenBy is missing"));
        assertTrue(malformedReason("{\"formatVersion\":1,\"writtenBy\":2}").contains("writtenBy is missing"));
    }

    @Test
    public void test_holds_data_only_once_the_admin_database_exists() throws IOException {
        assertFalse(file.holdsData());

        Files.createDirectories(tmp.resolve(Globals.ADMIN_DB_NAME));

        assertTrue(file.holdsData());
    }
}
