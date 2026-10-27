package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.test.TestUtils;

public class WalCheckpointsTest {
    private static final String DB = "ckptDb";

    @TempDir
    File tmp;

    private static final CollectionTryLock FREE = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            return true;
        }

        @Override
        public void unlock(String dbName, String collName) {
        }
    };

    private CheckpointGuard previousGuard;
    private final List<ReplacerFixture> fixtures = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        previousGuard = TestUtils.getPrivateStaticField(WriteAheadLog.class, "guard", CheckpointGuard.class);
        WriteAheadLog.guardWith(CheckpointGuard.NO_INDEX_WORK);
    }

    @AfterEach
    public void tearDown() {
        WriteAheadLog.guardWith(previousGuard);
    }

    private ReplacerFixture collection(String collName) throws IOException {
        final var fixture = new ReplacerFixture(tmp, DB, collName);
        fixture.fx.seed(0, "a", "b");
        fixtures.add(fixture);
        return fixture;
    }

    private static int records(ReplacerFixture fixture) throws IOException {
        final var log = fixture.fx.paths.writeAheadLog(DB, fixture.fx.collName);
        return WalCodec.decodeAll(Files.readAllBytes(log.toPath())).records().size();
    }

    private WalCheckpoints checkpoints() {
        return new WalCheckpoints(fixtures.getFirst().fx.paths, new WriteAheadLog(fixtures.getFirst().fx.paths));
    }

    private static CheckpointGuard holdingIndexWorkOnBusy() {
        return new CheckpointGuard() {
            @Override
            public boolean holdsIndexWork(String dbName, String coll) {
                return "busy".equals(coll);
            }

            @Override
            public Collection<String> pendingIds(String dbName, String coll) {
                return List.of();
            }
        };
    }

    @Test
    public void test_every_dirty_log_is_checkpointed_at_shutdown() throws Exception {
        for (var i = 0; i < 50; i++) {
            collection("coll" + i);
        }

        checkpoints().atShutdown(Long.MAX_VALUE);

        for (final var fixture : fixtures) {
            assertEquals(0, records(fixture), fixture.fx.collName + " kept its records");
        }
    }

    @Test
    public void test_an_expired_deadline_leaves_every_log_for_replay() throws Exception {
        final var first = collection("first");
        final var second = collection("second");

        checkpoints().atShutdown(System.currentTimeMillis() - 1);

        assertEquals(2, records(first));
        assertEquals(2, records(second));
    }

    @Test
    public void test_a_log_with_pending_index_work_is_left_for_replay() throws Exception {
        final var busy = collection("busy");
        final var quiet = collection("quiet");
        WriteAheadLog.guardWith(holdingIndexWorkOnBusy());

        checkpoints().atShutdown(Long.MAX_VALUE);

        assertEquals(2, records(busy));
        assertEquals(0, records(quiet));
        assertEquals(0, checkpoints().idle(0L, 16, FREE));
        assertEquals(2, records(busy));
    }

    @Test
    public void test_an_idle_pass_checkpoints_only_logs_idle_past_the_threshold() throws Exception {
        final var fixture = collection("idle");

        assertEquals(0, checkpoints().idle(60_000L, 16, FREE));
        assertEquals(2, records(fixture));

        Thread.sleep(20);
        assertEquals(1, checkpoints().idle(10L, 16, FREE));
        assertEquals(0, records(fixture));
        assertEquals(0, checkpoints().idle(0L, 16, FREE), "a checkpointed log is no longer dirty");
    }

    @Test
    public void test_an_idle_pass_stops_at_its_log_budget() throws Exception {
        for (var i = 0; i < 5; i++) {
            collection("budget" + i);
        }

        assertEquals(3, checkpoints().idle(0L, 3, FREE));
        assertEquals(2, checkpoints().idle(0L, 3, FREE));
    }

    @Test
    public void test_an_open_write_group_is_never_checkpointed_while_idle() throws Exception {
        final var fixture = collection("grouped");
        final var log = new WriteAheadLog(fixture.fx.paths);
        log.openGroup(DB, "grouped");
        try {
            assertEquals(0, checkpoints().idle(0L, 16, FREE));
            assertFalse(log.checkpointIfIdle(DB, "grouped", 0L));
        } finally {
            log.closeGroup(DB, "grouped");
        }
        assertEquals(2, records(fixture));
    }

    @Test
    public void test_the_idle_check_is_repeated_under_the_lock() throws Exception {
        final var fixture = collection("recheck");
        final var log = new WriteAheadLog(fixture.fx.paths);

        assertFalse(log.checkpointIfIdle(DB, "recheck", 60_000L));
        assertFalse(log.checkpointIfIdle(DB, "unknown", 0L));
        assertTrue(log.checkpointIfIdle(DB, "recheck", 0L));
        assertFalse(log.checkpointIfIdle(DB, "recheck", 0L), "nothing is left to checkpoint");
    }

    @Test
    public void test_an_idle_pass_skips_a_collection_whose_lock_is_taken_and_unlocks_what_it_took() throws Exception {
        final var held = collection("held");
        final var free = collection("free");
        final var unlocked = new ArrayList<String>();
        final var locks = new CollectionTryLock() {
            @Override
            public boolean tryLock(String dbName, String collName) {
                return !"held".equals(collName);
            }

            @Override
            public void unlock(String dbName, String collName) {
                unlocked.add(collName);
            }
        };

        assertEquals(1, checkpoints().idle(0L, 16, locks));
        assertEquals(2, records(held), "a busy collection keeps its log for a later pass");
        assertEquals(0, records(free));
        assertEquals(List.of("free"), unlocked);
    }
}
