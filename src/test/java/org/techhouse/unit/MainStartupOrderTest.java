package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class MainStartupOrderTest {
    private static final Path MAIN_SOURCE = Path.of("src", "main", "java", "org", "techhouse", "Main.java");
    private static final String RECOVER_LOCAL = "TriggerRunRecovery.recoverLocal(startupTriggerRuns);";

    private static String mainBody() throws IOException {
        final var source = Files.readString(MAIN_SOURCE);
        return source.substring(source.indexOf("public static void main("));
    }

    private static int positionOf(String body, String call) {
        final var position = body.indexOf(call);
        assertTrue(position >= 0, "Main.main no longer calls " + call);
        return position;
    }

    @Test
    public void test_the_clock_is_seeded_before_transaction_recovery() throws IOException {
        final var body = mainBody();

        assertTrue(positionOf(body, "seedHybridClock();") < positionOf(body, "cleanupOrphanedTransactions();"),
                "recovery replays writes through the hybrid clock, so an unseeded clock stamps them with versions"
                        + " below what this node already replicated and last-write-wins reverts the commit");
    }

    @Test
    public void interruptedCompactionsAreRecoveredBeforeAdminDataIsLoaded() throws IOException {
        final var body = mainBody();
        final var recovery = positionOf(body, "fs.recoverInterruptedCompactions();");

        assertTrue(positionOf(body, "fs.createAdminDatabase();") < recovery,
                "recovery walks the folders the admin database creates");
        assertTrue(recovery < positionOf(body, "cache.loadAdminData();"),
                "admin collections are journaled too, and loading them first would cache their stale pk index");
        assertTrue(recovery < positionOf(body, "seedHybridClock();"),
                "the clock must be seeded from the versions recovery leaves in pk.idx");
    }

    @Test
    public void test_trigger_recovery_runs_after_the_node_joins_the_cluster() throws IOException {
        final var body = mainBody();

        assertTrue(positionOf(body, "startClusterIfEnabled();") < positionOf(body, RECOVER_LOCAL),
                "every user write is refused for lack of quorum until the ownership ring is built, so a replay"
                        + " submitted before the join burns its attempts and dead-letters");
    }

    @Test
    public void test_trigger_runs_are_snapshotted_before_any_producer_starts() throws IOException {
        final var body = mainBody();
        final var snapshot = positionOf(body, "TriggerRunRecovery.startupRunIds();");

        assertTrue(positionOf(body, "cleanupOrphanedTransactions();") < snapshot,
                "runs recorded by startup recovery before the executor starts are exactly what must be replayed");
        assertTrue(snapshot < positionOf(body, "triggerExecutor.start("),
                "once the executor runs, a recorded run is also queued, and replaying it again runs it twice");
        assertTrue(snapshot < positionOf(body, "startSchedulerIfEnabled();"),
                "a scheduled procedure can record and queue a trigger run");
        assertTrue(snapshot < positionOf(body, "startClusterIfEnabled();"),
                "2PC recovery and forwarded writes can record and queue a trigger run");
    }

    @Test
    public void test_dirty_indexes_are_reported_before_startup_marks_anything() throws IOException {
        final var body = mainBody();
        final var warning = positionOf(body, "StartupWarnings.warnIfIndexesLeftDirty();");

        assertTrue(positionOf(body, "cache.loadAdminData();") < warning);
        assertTrue(warning < positionOf(body, "PageOccupancyReconciler.reconcileAll();"),
                "adopting an orphaned record marks it pending, and its event clears the marker of the unclean stop");
        assertTrue(warning < positionOf(body, "cleanupOrphanedTransactions();"),
                "a replayed commit marks and clears pending ids too");
        assertTrue(warning < positionOf(body, "backgroundTaskManager.startBackgroundWorkers();"),
                "a running worker drains the pending map and deletes the marker before it is reported");
    }

    @Test
    public void test_recovered_deletes_are_cleaned_after_page_rows_are_reconciled() throws IOException {
        final var body = mainBody();
        final var cleanup = positionOf(body, "PageOccupancyReconciler.scheduleIndexCleanupFor(recoveredDeletes);");

        assertTrue(positionOf(body, "fs.recoverInterruptedCompactions();") < cleanup);
        assertTrue(positionOf(body, "PageOccupancyReconciler.reconcileAll();") < cleanup,
                "the cleanup events carry no page delta because the rows were just rebuilt from the page files");
        assertTrue(cleanup < positionOf(body, "backgroundTaskManager.startBackgroundWorkers();"));
    }

    @Test
    public void test_trigger_recovery_replays_the_startup_snapshot() throws IOException {
        assertTrue(mainBody().contains(RECOVER_LOCAL),
                "recovery must replay only the runs pending before the executor started, not every pending run");
    }
}
