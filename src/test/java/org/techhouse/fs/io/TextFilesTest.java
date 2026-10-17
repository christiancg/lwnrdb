package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TextFilesTest {
    @TempDir
    Path tmp;

    @Test
    public void an_absent_file_reads_as_null() throws IOException {
        assertNull(TextFiles.readLinesIfExists(tmp.resolve("lwnrdb.cfg")));
    }

    @Test
    public void lines_are_decoded_as_utf8() throws IOException {
        final var file = Files.writeString(tmp.resolve("lwnrdb.cfg"), "port=8989\nname=café\n", StandardCharsets.UTF_8);
        assertEquals(List.of("port=8989", "name=café"), TextFiles.readLinesIfExists(file));
    }

    @Test
    public void a_directory_throws_io_exception() throws IOException {
        final var folder = Files.createDirectory(tmp.resolve("lwnrdb.cfg"));
        assertThrows(IOException.class, () -> TextFiles.readLinesIfExists(folder));
    }
}
