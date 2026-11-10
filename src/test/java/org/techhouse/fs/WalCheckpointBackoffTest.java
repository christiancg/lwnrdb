package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.test.TestUtils;

public class WalCheckpointBackoffTest {
    private static final String DB = "backoffDb";
    private static final String COLL = "backoffColl";
    private static final CollectionTryLock FREE = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            return true;
        }

        @Override
        public void unlock(String dbName, String collName) {
        }
    };

    @TempDir
    File tmp;

    private CheckpointGuard previousGuard;
    private ReplacerFixture fixture;
    private RecordingForcer recorder;

    @BeforeEach
    public void setUp() throws Exception {
        previousGuard = TestUtils.getPrivateStaticField(WriteAheadLog.class, "guard", CheckpointGuard.class);
        WriteAheadLog.guardWith(CheckpointGuard.NO_INDEX_WORK);
        fixture = new ReplacerFixture(tmp, DB, COLL);
        fixture.fx.seed(0, "a", "b");
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        recorder.close();
        WriteAheadLog.guardWith(previousGuard);
    }

    private WalCheckpoints checkpoints() {
        return new WalCheckpoints(fixture.fx.paths, new WriteAheadLog(fixture.fx.paths));
    }

    private WalLogState state() {
        return WalLogState.known(fixture.fx.paths.writeAheadLog(DB, COLL).getAbsolutePath());
    }

    private void failPkForces() {
        recorder.failingFor("-pk.idx");
    }

    @Test
    public void aFailedIdleCheckpointIsNotRetriedBeforeItsBackoff() {
        failPkForces();

        assertEquals(0, checkpoints().idle(0L, 16, FREE));
        assertEquals(1, state().checkpointFailures);
        assertEquals(0, checkpoints().idle(0L, 16, FREE));

        assertEquals(1, state().checkpointFailures, "the second pass waited out the backoff");
        assertTrue(state().checkpointRetryAtMillis > System.currentTimeMillis());
    }

    @Test
    public void theBackoffDoublesAndIsCapped() {
        final var state = state();
        final var now = 1_000_000L;
        final var delays = new long[8];
        for (var i = 0; i < delays.length; i++) {
            delays[i] = CheckpointBackoff.failed(state, now);
        }

        assertEquals(Globals.WAL_CHECKPOINT_RETRY_BASE_MS, delays[0]);
        assertEquals(2 * Globals.WAL_CHECKPOINT_RETRY_BASE_MS, delays[1]);
        assertEquals(4 * Globals.WAL_CHECKPOINT_RETRY_BASE_MS, delays[2]);
        assertEquals(Globals.WAL_CHECKPOINT_RETRY_MAX_MS, delays[7]);
        assertEquals(now + Globals.WAL_CHECKPOINT_RETRY_MAX_MS, state.checkpointRetryAtMillis);
    }

    @Test
    public void aSuccessResetsTheStreak() {
        failPkForces();
        checkpoints().idle(0L, 16, FREE);
        recorder.failingFor(null);
        state().checkpointRetryAtMillis = 0L;

        assertEquals(1, checkpoints().idle(0L, 16, FREE));

        assertEquals(0, state().checkpointFailures);
        assertEquals(0L, state().checkpointRetryAtMillis);
    }

    @Test
    public void aRequestedCheckpointRespectsTheBackoff() {
        failPkForces();
        checkpoints().idle(0L, 16, FREE);
        final var log = new WriteAheadLog(fixture.fx.paths);
        WalCheckpointRequests.request(fixture.fx.paths.writeAheadLog(DB, COLL).getAbsolutePath(), DB, COLL);

        assertEquals(0, checkpoints().requested(FREE));

        assertEquals(1, state().checkpointFailures, "the request did not try while the log backs off");
        assertTrue(log.statusesUnder(tmp.getAbsolutePath()).stream().anyMatch(WriteAheadLog.WalLogStatus::dirty));
    }

    @Test
    public void aNullStateNeverWaitsOrCounts() {
        assertEquals(0L, CheckpointBackoff.failed(null, 1L));
        assertEquals(0, CheckpointBackoff.succeeded(null));
    }
}
