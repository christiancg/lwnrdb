package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Configuration;
import org.techhouse.test.TestUtils;

public class WalBackgroundCheckpointTest {
    private static final String DB = "bgDb";
    private static final String COLL = "bgColl";
    private static final long SHIPPED_CHECKPOINT_BYTES = 8L * 1024 * 1024;
    private static final String[] SEEDED = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"};

    @TempDir
    File tmp;

    private final AtomicInteger submitted = new AtomicInteger();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final List<String> lockCalls = new ArrayList<>();
    private ReplacerFixture rf;

    private final CollectionTryLock recordingLocks = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            lockCalls.add("lock " + collName);
            return true;
        }

        @Override
        public void unlock(String dbName, String collName) {
            lockCalls.add("unlock " + collName);
        }
    };

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, SEEDED);
        WalCheckpointRequests.takeAll();
        WalCheckpointRequests.listenWith(() -> {
            submitted.incrementAndGet();
            return accepting.get();
        });
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", OutsideWrites.logEnd(log()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", SHIPPED_CHECKPOINT_BYTES);
        WalCheckpointRequests.listenWith(null);
        WalCheckpointRequests.takeAll();
        WriteAheadLog.forgetUnder(tmp);
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private int records() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log().toPath())).records().size();
    }

    private WalCheckpoints checkpoints() {
        return new WalCheckpoints(rf.fx.paths, new WriteAheadLog(rf.fx.paths));
    }

    @Test
    public void fullLogQueuesBackgroundCheckpointUnderReadLock() throws Exception {
        rf.replace("a", "new-a", 0, PagePlacement.APPEND);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        assertEquals(SEEDED.length + 2, records(), "the writer leaves the checkpoint to the background");
        assertEquals(1, submitted.get(), "one request is queued however many writes pass the size");
        assertTrue(WalCheckpointRequests.isRequested(log().getAbsolutePath()));

        assertEquals(1, checkpoints().requested(recordingLocks));

        assertEquals(0, records());
        assertEquals(List.of("lock " + COLL, "unlock " + COLL), lockCalls);
        assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")));
        assertEquals(0, checkpoints().requested(recordingLocks), "a served request is gone");
    }

    @Test
    public void writerCheckpointsSynchronouslyPastTwiceTheBytes() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "walCheckpointBytes", OutsideWrites.logEnd(log()) / 3);

        rf.replace("a", "new-a", 0, PagePlacement.APPEND);

        assertEquals(0, records(), "a log past twice its size is checkpointed by the writer itself");
        assertEquals(0, submitted.get());
    }

    @Test
    public void aRequestNobodyAcceptsIsDropped() throws Exception {
        accepting.set(false);

        rf.replace("a", "new-a", 0, PagePlacement.APPEND);

        assertFalse(WalCheckpointRequests.isRequested(log().getAbsolutePath()));
        accepting.set(true);
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        assertEquals(2, submitted.get(), "a refused request does not swallow the next one");
    }

    @Test
    public void aBusyCollectionKeepsItsLogUntilTheNextRequest() throws Exception {
        rf.replace("a", "new-a", 0, PagePlacement.APPEND);
        final var refused = new CollectionTryLock() {
            @Override
            public boolean tryLock(String dbName, String collName) {
                return false;
            }

            @Override
            public void unlock(String dbName, String collName) {
                throw new AssertionError("nothing was locked");
            }
        };

        assertEquals(0, checkpoints().requested(refused));

        assertEquals(SEEDED.length + 1, records());
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        assertEquals(1, checkpoints().requested(recordingLocks));
        assertEquals(0, records());
    }

    @Test
    public void anOpenGroupOrAnUnappliedLogIsLeftToItsWriter() throws Exception {
        rf.replace("a", "new-a", 0, PagePlacement.APPEND);
        final var wal = new WriteAheadLog(rf.fx.paths);
        wal.openGroup(DB, COLL);
        try {
            assertFalse(wal.checkpointRequested(DB, COLL), "a group's records wait for its own settle");
        } finally {
            assertTrue(wal.closeGroup(DB, COLL));
        }
        wal.markUnapplied(DB, COLL);
        assertFalse(wal.checkpointRequested(DB, COLL), "a replay needs the collection write lock");

        assertTrue(wal.requireApplied(DB, COLL), "the collection's next user applies the log");
        rf.replace("b", "new-b", 0, PagePlacement.APPEND);
        assertTrue(wal.checkpointRequested(DB, COLL), "the log is applied");
        assertEquals(0, records());
    }

    @Test
    public void aDroppedCollectionForgetsItsRequest() throws Exception {
        rf.replace("a", "new-a", 0, PagePlacement.APPEND);
        final var folder = rf.fx.paths.collectionFolder(DB, COLL);

        assertTrue(FileTreeDeleter.delete(folder));

        assertFalse(WalCheckpointRequests.isRequested(log().getAbsolutePath()));
        assertEquals(0, checkpoints().requested(recordingLocks));
        assertEquals(List.of(), lockCalls, "a dropped collection's lock is never taken, so never recreated");
        assertFalse(folder.exists());
    }
}
