package org.techhouse.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

/**
 * Durable log of trigger runs that have been queued but not yet applied, stored in {@code admin/trigger_runs}.
 *
 * <p>
 * A record is written before the run's events are queued and consumed inside the very transaction that
 * applies the run's effects (see {@code TriggerDispatcher}), so the two commit or roll back together: a run
 * whose effects landed leaves no record to replay, and a run that left a record did not land. That is what
 * makes replay after a crash exactly-once rather than at-least-once.
 */
public final class TriggerRunLog {
    // Recovery only replays records this node wrote. A standalone node has no cluster identity, so it uses a
    // fixed id that is stable across restarts, which is all the filter needs.
    private static final String STANDALONE_NODE_ID = "local";
    // Headroom for the fixed fields of a record, so a chunk sized against maxEntrySize still fits once the
    // metadata around the id/document list is serialized.
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

    /**
     * Persists the run and returns its id, or {@code null} when nothing was persisted — the log is disabled,
     * or a single DELETED document is too large to store, in which case the caller submits the run
     * non-durably rather than failing a write that has already committed.
     */
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

    public static List<AdminTriggerRunEntry> pending() throws Exception {
        return AdminOperationHelper.readTriggerRuns(new ArrayList<>(cache.getTriggerRunPkIndexes().keySet()));
    }

    // Drops records left behind by a run that can never complete - its collection was dropped, or the node
    // that owned it never came back - so the log cannot grow without bound.
    public static void garbageCollect(long retentionMs) throws Exception {
        final var cutoff = System.currentTimeMillis() - retentionMs;
        final var stale = new ArrayList<String>();
        for (final var entry : pending()) {
            if (entry.getFiredAt() < cutoff) {
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
        return self == null ? STANDALONE_NODE_ID : self.getNodeId();
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

    // A DELETED run stores the documents themselves, since they are already gone from the collection and
    // cannot be re-read at replay time. Returns null when one document alone cannot fit in a record.
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
