package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.test.TestUtils;

public class WalPendingFrameTest {
    private static final String DB = "pendingDb";
    private static final String COLL = "pendingColl";
    private static final int LARGE_PENDING_IDS = 2000;
    private static final long SMALL_CHECKPOINT_BYTES = 4096L;

    @TempDir
    File tmp;

    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private CheckpointGuard previousGuard;
    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        previousGuard = TestUtils.getPrivateStaticField(WriteAheadLog.class, "guard", CheckpointGuard.class);
        WriteAheadLog.guardWith(new CheckpointGuard() {
            @Override
            public boolean holdsIndexWork(String dbName, String collName) {
                return !pending.isEmpty();
            }

            @Override
            public Collection<String> pendingIds(String dbName, String collName) {
                return List.copyOf(pending);
            }
        });
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b");
    }

    @AfterEach
    public void tearDown() {
        WriteAheadLog.guardWith(previousGuard);
        WriteAheadLog.forgetUnder(tmp);
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private WalCodec.Decoded decoded() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log().toPath()));
    }

    private boolean dirty(WriteAheadLog wal) {
        return wal.statusesUnder(tmp.getAbsolutePath() + File.separator).stream()
                .filter(status -> COLL.equals(status.collName())).findFirst().orElseThrow().dirty();
    }

    private List<String> replayedIds() throws IOException {
        WriteAheadLog.forgetUnder(tmp);
        return WalRedo.redoAll(rf.fx.paths).stream().map(PkIndexEntry::getValue).toList();
    }

    @Test
    public void pendingOnlyLogIsDirtyOnceResolved() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        pending.add("a");
        wal.checkpoint(DB, COLL);
        assertTrue(decoded().pendingLogged());
        assertEquals(List.of("a"), decoded().pendingIds());
        assertFalse(dirty(wal), "a frame whose ids are still pending is up to date");

        pending.clear();

        assertTrue(dirty(wal), "a frame naming only resolved ids is stale");
        wal.checkpoint(DB, COLL);
        assertFalse(decoded().pendingLogged());
        assertEquals(List.of(), decoded().pendingIds());
        assertFalse(dirty(wal));
    }

    @Test
    public void pendingOnlyLogStaysWhileIndexWorkHeld() throws Exception {
        final var wal = new WriteAheadLog(rf.fx.paths);
        pending.add("a");
        wal.checkpoint(DB, COLL);
        final var framed = Files.readAllBytes(log().toPath());

        try (var recorder = RecordingForcer.install(true)) {
            wal.checkpoint(DB, COLL);
            new WalCheckpoints(rf.fx.paths, wal).atShutdown(Long.MAX_VALUE);

            assertEquals(List.of(), recorder.forced(), "nothing is forced or rewritten for a frame still in use");
        }
        assertArrayEquals(framed, Files.readAllBytes(log().toPath()));
    }

    @Test
    public void restartAfterResolvedPendingReplaysNothing() throws Exception {
        pending.add("b");
        new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
        assertEquals(List.of("b"), replayedIds(), "the first restart re-queues the pending id");

        pending.clear();
        new WalCheckpoints(rf.fx.paths, new WriteAheadLog(rf.fx.paths)).atShutdown(Long.MAX_VALUE);

        assertFalse(decoded().pendingLogged(), "the clean stop dropped the resolved frame");
        assertEquals(List.of(), replayedIds(), "the second restart has nothing to repair");
    }

    @Test
    public void aLargePendingFrameDoesNotForceSynchronousCheckpoints() throws Exception {
        final var configuration = Configuration.getInstance();
        final var shipped = configuration.getWalCheckpointBytes();
        try {
            for (var index = 0; index < LARGE_PENDING_IDS; index++) {
                pending.add("pending-id-" + index);
            }
            new WriteAheadLog(rf.fx.paths).checkpoint(DB, COLL);
            TestUtils.setPrivateField(configuration, "walCheckpointBytes", SMALL_CHECKPOINT_BYTES);
            assertTrue(log().length() > 2 * SMALL_CHECKPOINT_BYTES, "the frame alone is past twice the limit");

            rf.replace("a", "new-a", 0, PagePlacement.APPEND);
            assertEquals(1, decoded().records().size(), "the write is logged, not checkpointed away");

            WriteAheadLog.forgetUnder(tmp);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            assertEquals(2, decoded().records().size(), "a reseeded log knows its frame size too");
        } finally {
            TestUtils.setPrivateField(configuration, "walCheckpointBytes", shipped);
        }
    }
}
