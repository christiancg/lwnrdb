package org.techhouse.unit.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;

public class GlobalsTest {
    @Test
    public void test_writes_are_not_opened_in_a_device_synchronous_mode() {
        assertEquals("rw", Globals.RW_PERMISSIONS,
                "durability is best-effort by decision: 'rwd'/'rws' were dropped because they covered only "
                        + "four of the write paths and not the PK index, so nothing was durable end to end. "
                        + "See the invariant in CLAUDE.md before changing this.");
    }

    @Test
    public void test_the_read_mode_stays_read_only() {
        assertEquals("r", Globals.R_PERMISSIONS);
    }

    @Test
    public void test_both_modes_are_accepted_by_RandomAccessFile() throws IOException {
        final var file = File.createTempFile("globals-mode", ".dat");
        try {
            try (var writer = new RandomAccessFile(file, Globals.RW_PERMISSIONS)) {
                writer.write("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try (var reader = new RandomAccessFile(file, Globals.R_PERMISSIONS)) {
                assertEquals(7L, reader.length());
            }
        } finally {
            Files.deleteIfExists(file.toPath());
        }
    }
}
