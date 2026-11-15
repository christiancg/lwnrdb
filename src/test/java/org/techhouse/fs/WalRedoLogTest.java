package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.PlatformSupport;
import org.techhouse.test.TestUtils;

public class WalRedoLogTest {
    @TempDir
    File tmp;

    private RecordingForcer recorder;
    private WalRedoFixture redoFixture;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        recorder = RecordingForcer.install(PlatformSupport.directorySync());
        redoFixture = new WalRedoFixture(tmp);
        rf = redoFixture.rf;
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
    }

    private List<PkIndexEntry> redo() throws IOException {
        return redoFixture.redo();
    }

    private File log() {
        return redoFixture.log();
    }

    private void assertHolds(Map<String, String> expected) throws IOException {
        redoFixture.assertHolds(expected);
    }

    @Test
    public void test_without_synced_writes_unlogged_bytes_are_kept() throws Exception {
        final var page = rf.fx.page(0);
        Files.writeString(page.toPath(), StorageFixture.record("z", "maybe-acknowledged"), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        final var landed = page.length();
        TestUtils.setPrivateField(Configuration.getInstance(), "syncWrites", "false");

        redo();

        assertEquals(landed, page.length(), "unsynced, those bytes may be an acknowledged write whose record was lost");
    }
    @Test
    public void test_a_clean_log_replays_nothing_and_stays_a_checkpoint() throws Exception {
        final var before = Files.readAllBytes(rf.fx.page(0).toPath());

        assertEquals(List.of(), redo());

        assertArrayEquals(before, Files.readAllBytes(rf.fx.page(0).toPath()));
        assertEquals(0, WalCodec.decodeAll(Files.readAllBytes(log().toPath())).framesSinceCheckpoint());
    }
    @Test
    public void test_a_log_without_a_checkpoint_is_left_alone() throws Exception {
        Files.write(log().toPath(), new byte[]{1, 2, 3});

        assertEquals(List.of(), redo());

        assertHolds(WalRedoFixture.original());
    }
}
