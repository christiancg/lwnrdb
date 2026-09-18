package org.techhouse.unit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public class MainStartupOrderTest {
    private static final Path MAIN_SOURCE = Path.of("src", "main", "java", "org", "techhouse", "Main.java");

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
    public void test_trigger_recovery_runs_after_the_node_joins_the_cluster() throws IOException {
        final var body = mainBody();

        assertTrue(
                positionOf(body, "startClusterIfEnabled();") < positionOf(body, "TriggerRunRecovery.recoverLocal();"),
                "every user write is refused for lack of quorum until the ownership ring is built, so a replay"
                        + " submitted before the join burns its attempts and dead-letters");
    }
}
