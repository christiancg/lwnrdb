package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.log.Logger;

public final class TriggerDeadLetterCap {
    private static final Logger logger = Logger.logFor(TriggerDeadLetterCap.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final ReentrantLock ENFORCING = new ReentrantLock();
    private static final Set<String> overCap = ConcurrentHashMap.newKeySet();
    private static final Comparator<DeadRun> OLDEST_FIRST = Comparator.comparingLong(DeadRun::diedAt)
            .thenComparing(DeadRun::runId);

    private TriggerDeadLetterCap() {
    }

    private static final class DeadRun {
        private final String runId;
        private final List<String> recordIds = new ArrayList<>(1);
        private long diedAt;

        private DeadRun(String runId) {
            this.runId = runId;
        }

        private String runId() {
            return runId;
        }

        private long diedAt() {
            return diedAt;
        }
    }

    public static void afterDeadLetter(String dbName) {
        final var cap = cap();
        if (cap <= 0 || dbName == null) {
            return;
        }
        if (TriggerRunIndex.knownDeadIn(dbName) <= cap) {
            overCap.remove(dbName);
            return;
        }
        try {
            enforce(dbName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Interrupted while capping the dead-lettered trigger runs of " + dbName);
        } catch (IOException | RuntimeException e) {
            logger.warning("Failed to cap the dead-lettered trigger runs of " + dbName + ": " + e.getMessage());
        }
    }

    public static void forgetDatabase(String dbName) {
        overCap.remove(dbName);
    }

    public static int enforce(String dbName) throws IOException, InterruptedException {
        final var cap = cap();
        if (cap <= 0) {
            overCap.clear();
            return 0;
        }
        ENFORCING.lock();
        try {
            final var doomed = new ArrayList<String>();
            var dropped = 0;
            for (final var database : deadRunsByDatabase(dbName).entrySet()) {
                final var runs = database.getValue();
                if (runs.size() <= cap) {
                    overCap.remove(database.getKey());
                    continue;
                }
                final var oldest = new ArrayList<>(runs.values());
                oldest.sort(OLDEST_FIRST);
                final var excess = (int) (runs.size() - cap);
                for (final var run : oldest.subList(0, excess)) {
                    doomed.addAll(run.recordIds);
                }
                dropped += excess;
                if (overCap.add(database.getKey())) {
                    logger.warning("Database '" + database.getKey() + "' holds " + runs.size()
                            + " dead-lettered trigger runs, more than triggerDeadLetterMaxRuns=" + cap
                            + "; deleting the oldest ones beyond the cap. Further deletions are not logged until it"
                            + " drops back under the cap.");
                }
            }
            if (!doomed.isEmpty()) {
                AdminOperationHelper.deleteTriggerRuns(doomed);
            }
            return dropped;
        } finally {
            ENFORCING.unlock();
        }
    }

    private static Map<String, Map<String, DeadRun>> deadRunsByDatabase(String dbName)
            throws IOException, InterruptedException {
        final var byDatabase = new HashMap<String, Map<String, DeadRun>>();
        for (final var summary : TriggerRunIndex.current()) {
            final var database = summary.dbName();
            if (!summary.isDead() || database == null || dbName != null && !dbName.equals(database)) {
                continue;
            }
            final var run = byDatabase.computeIfAbsent(database, _ -> new HashMap<>()).computeIfAbsent(summary.runId(),
                    DeadRun::new);
            run.recordIds.add(summary.recordId());
            run.diedAt = Math.max(run.diedAt, summary.diedAt());
        }
        return byDatabase;
    }

    private static long cap() {
        return configuration.longValue(ConfigKey.TRIGGER_DEAD_LETTER_MAX_RUNS);
    }
}
