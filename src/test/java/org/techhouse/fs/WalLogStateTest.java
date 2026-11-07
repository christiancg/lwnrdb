package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class WalLogStateTest {
    private static final String DB = "walStateDb";
    private static final String COLL = "walStateColl";

    @TempDir
    File tmp;

    private File log;

    @BeforeEach
    public void setUp() throws Exception {
        final var fixture = new StorageFixture(tmp, DB, COLL);
        fixture.seed(0, "a", "b");
        log = fixture.paths.writeAheadLog(DB, COLL);
    }

    private WalLogState state() throws IOException {
        return WalLogState.of(log, log.getAbsolutePath(), DB, COLL);
    }

    @Test
    public void test_a_seeded_state_is_answered_without_reading_the_log() throws IOException {
        final var seeded = state();
        final var known = seeded.knownLength;
        Files.write(log.toPath(), new byte[]{'W', 'R', 0}, StandardOpenOption.APPEND);

        assertSame(seeded, state());
        assertEquals(known, state().knownLength, "the log is not statted again");
    }

    @Test
    public void test_a_state_marked_for_reseeding_reads_the_log_again() throws IOException {
        final var known = state().knownLength;
        Files.write(log.toPath(), new byte[]{'W', 'R', 0}, StandardOpenOption.APPEND);

        state().reseedOnNextUse();

        assertEquals(known, state().knownLength);
        final var bytes = Files.readAllBytes(log.toPath());
        assertEquals(bytes.length, WalWriteGroup.writtenEnd(bytes, known) == known ? bytes.length : -1,
                "the reseed erases the torn end it finds");
    }
}
