package org.techhouse.unit.ops.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.fs.RetiredCopies;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.snapshot.SnapshotSweeper;

public class SnapshotRegistryTest {
    private final SnapshotTestSupport support = new SnapshotTestSupport();

    @BeforeEach
    public void setUp() throws Exception {
        support.setUp();
    }

    @AfterEach
    public void tearDown() throws Exception {
        support.tearDown();
    }

    @Test
    public void test_only_a_database_with_an_open_snapshot_is_watched() {
        assertFalse(support.registry.watches(SnapshotTestSupport.DB));
        final var snapshotId = support.start();

        assertTrue(support.registry.watches(SnapshotTestSupport.DB));
        assertFalse(support.registry.watches("elsewhere"));
        support.release(support.client, snapshotId);
        assertFalse(support.registry.watches(SnapshotTestSupport.DB));
    }

    @Test
    public void test_an_old_line_that_cannot_be_read_aborts_the_snapshot_and_lets_the_write_go_on() {
        final var snapshotId = support.start();
        final var copy = new RetiredCopies.RetiredCopy("d0", 0L, 0L, 10L);

        support.registry.retiring(SnapshotTestSupport.DB, SnapshotTestSupport.COLL, List.of(copy), List.of(),
                ignored -> {
                    throw new IOException("disk full");
                });

        final var read = support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5);
        assertEquals(ErrorCode.SNAPSHOT_ABORTED.getCode(), read.getErrorCode());
        assertTrue(read.getMessage().contains("disk full"), read.getMessage());
        assertFalse(support.registry.watches(SnapshotTestSupport.DB));
    }

    @Test
    public void test_a_collection_outside_the_snapshot_is_never_imaged() {
        final var snapshotId = support.start();
        support.registry.retiring(SnapshotTestSupport.DB, "notCaptured",
                List.of(new RetiredCopies.RetiredCopy("x", 0L, 0L, 1L), new RetiredCopies.RetiredCopy("y", 0L, 0L, 1L)),
                List.of(), ignored -> {
                    throw new IOException("must not be read");
                });

        assertEquals("OK",
                support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5).getStatus().name());
    }

    @Test
    public void test_two_snapshots_of_one_database_keep_their_own_versions() {
        support.save("d0", 0);
        final var first = support.start();
        support.save("d0", 1);
        final var second = support.start();
        support.save("d0", 2);

        assertEquals(List.of("d0=0"), support.summaries(support.readAll(first, SnapshotTestSupport.COLL, 5)));
        assertEquals(List.of("d0=1"), support.summaries(support.readAll(second, SnapshotTestSupport.COLL, 5)));
    }

    @Test
    public void test_dropping_the_database_aborts_its_snapshots() {
        final var snapshotId = support.start();

        support.registry.abortDatabase(SnapshotTestSupport.DB, "its database was dropped");

        assertEquals(ErrorCode.SNAPSHOT_ABORTED.getCode(),
                support.read(support.client, snapshotId, SnapshotTestSupport.COLL, null, 5).getErrorCode());
        assertEquals(1, support.registry.openCount(), "an aborted snapshot answers 409-17 until released");
        assertEquals(1, support.registry.releaseAll());
    }

    @Test
    public void test_the_sweeper_starts_once_and_stops() {
        final var sweeper = IocContainer.get(SnapshotSweeper.class);
        try {
            sweeper.start();
            sweeper.start();

            assertTrue(sweeper.isRunning());
        } finally {
            sweeper.stop();
        }
        assertFalse(sweeper.isRunning());
        sweeper.stop();
    }
}
