package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public final class TriggerRunLog {
    private static final int CHUNK_OVERHEAD_BYTES = 2048;

    private static final Logger logger = Logger.logFor(TriggerRunLog.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final Configuration configuration = Configuration.getInstance();

    public record TriggerRunDescriptor(String dbName, String collName, String triggerName, String procedureName,
            EventType eventType, boolean batchMode, String actingUser, int depth, long firedAt, List<DbEntry> entries) {
    }

    private TriggerRunLog() {
    }

    public static boolean isEnabled() {
        return configuration.isTriggerRunLogEnabled();
    }

    public static String record(TriggerRunDescriptor descriptor) {
        if (!isEnabled()) {
            return null;
        }
        final var runId = UUID.randomUUID().toString();
        try {
            final var chunks = descriptor.eventType() == EventType.DELETED
                    ? documentChunks(descriptor.entries())
                    : idChunks(descriptor.entries());
            if (chunks == null) {
                logger.warning("Trigger '" + descriptor.triggerName() + "' on " + descriptor.dbName() + "|"
                        + descriptor.collName()
                        + " could not be logged durably: a document exceeds maxEntrySize. Running it without a"
                        + " durable record, so it will be lost if this node dies before it completes.");
                return null;
            }
            for (var chunkSeq = 0; chunkSeq < chunks.size(); chunkSeq++) {
                AdminOperationHelper
                        .saveTriggerRun(chunks.get(chunkSeq).toEntry(runId, chunkSeq, currentNodeId(), descriptor));
            }
            return runId;
        } catch (Exception e) {
            logger.warning("Failed to record the pending trigger run for '" + descriptor.triggerName() + "' on "
                    + descriptor.dbName() + "|" + descriptor.collName() + ": " + e.getMessage());
            return null;
        }
    }

    public static List<String> recordIdsFor(String runId) {
        final var result = new ArrayList<String>();
        for (final var key : cache.getTriggerRunPkIndexes().keySet()) {
            if (AdminTriggerRunEntry.runIdOf(key).equals(runId)) {
                result.add(key);
            }
        }
        return result;
    }

    public static void markAttempt(String runId, TriggerRunStatus status, int attempts, String error,
            long nextAttemptAt) {
        if (runId == null || !isEnabled()) {
            return;
        }
        try {
            for (final var entry : AdminOperationHelper.readTriggerRuns(recordIdsFor(runId))) {
                entry.markAttempt(status, attempts, error, nextAttemptAt);
                AdminOperationHelper.saveTriggerRun(entry);
            }
        } catch (Exception e) {
            logger.warning(
                    "Failed to record attempt " + attempts + " of trigger run '" + runId + "': " + e.getMessage());
        }
    }

    public static List<AdminTriggerRunEntry> pending() throws Exception {
        return AdminOperationHelper.readTriggerRuns(new ArrayList<>(cache.getTriggerRunPkIndexes().keySet()));
    }

    public static void garbageCollect(long retentionMs) throws Exception {
        garbageCollect(retentionMs, retentionMs);
    }

    public static void garbageCollect(long retentionMs, long deadLetterRetentionMs) throws Exception {
        final var now = System.currentTimeMillis();
        final var cutoff = now - retentionMs;
        final var deadCutoff = now - deadLetterRetentionMs;
        final var stale = new ArrayList<String>();
        for (final var entry : pending()) {
            final var limit = entry.getStatus() == TriggerRunStatus.DEAD ? deadCutoff : cutoff;
            if (entry.getFiredAt() < limit) {
                stale.add(entry.get_id());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        AdminOperationHelper.deleteTriggerRuns(stale);
        logger.info("Garbage-collected " + stale.size() + " stranded trigger run record(s)");
    }

    public static String currentNodeId() {
        final var self = membershipService.getSelf();
        return self == null ? Globals.STANDALONE_NODE_ID : self.getNodeId();
    }

    private record Chunk(List<String> ids, List<JsonObject> documents) {
        AdminTriggerRunEntry toEntry(String runId, long chunkSeq, String nodeId, TriggerRunDescriptor descriptor) {
            return new AdminTriggerRunEntry(runId, chunkSeq, nodeId, descriptor.dbName(), descriptor.collName(),
                    descriptor.triggerName(), descriptor.procedureName(), descriptor.eventType(),
                    descriptor.batchMode(), descriptor.actingUser(), descriptor.depth(), descriptor.firedAt(), ids,
                    documents);
        }
    }

    private static List<Chunk> idChunks(List<DbEntry> entries) {
        final var budget = chunkBudget();
        final var chunks = new ArrayList<Chunk>();
        var current = new ArrayList<String>();
        var currentBytes = 0L;
        for (final var entry : entries) {
            final var size = (long) entry.get_id().length() + 4L;
            if (!current.isEmpty() && currentBytes + size > budget) {
                chunks.add(new Chunk(current, List.of()));
                current = new ArrayList<>();
                currentBytes = 0L;
            }
            current.add(entry.get_id());
            currentBytes += size;
        }
        chunks.add(new Chunk(current, List.of()));
        return chunks;
    }

    private static List<Chunk> documentChunks(List<DbEntry> entries) {
        final var budget = chunkBudget();
        final var chunks = new ArrayList<Chunk>();
        var current = new ArrayList<JsonObject>();
        var currentBytes = 0L;
        for (final var entry : entries) {
            final var size = (long) entry.byteSize();
            if (size > budget) {
                return null;
            }
            if (!current.isEmpty() && currentBytes + size > budget) {
                chunks.add(new Chunk(List.of(), current));
                current = new ArrayList<>();
                currentBytes = 0L;
            }
            current.add(entry.getData());
            currentBytes += size;
        }
        chunks.add(new Chunk(List.of(), current));
        return chunks;
    }

    private static long chunkBudget() {
        return Math.max(1L, configuration.getMaxEntrySize() - CHUNK_OVERHEAD_BYTES);
    }
}
