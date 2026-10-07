package org.techhouse.ops;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int STAGED_VERSION_OVERHEAD_BYTES = 28;
    private static final String BATCH_RUN_KEY = "*";

    private static final Logger logger = Logger.logFor(TriggerRunLog.class);
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private static final org.techhouse.cluster.ClusterConfig clusterConfig = IocContainer
            .get(org.techhouse.cluster.ClusterConfig.class);
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
        return write(descriptor, UUID.randomUUID().toString(), null, null);
    }

    public static String recordStaged(TriggerRunDescriptor descriptor, Map<String, Long> priorVersions) {
        return write(descriptor, UUID.randomUUID().toString(), priorVersions, null);
    }

    public static String recordDeterministic(TriggerRunDescriptor descriptor, String txId, String runId) {
        if (!isEnabled()) {
            return null;
        }
        discard(runId);
        return write(descriptor, runId, null, txId);
    }

    public static String deterministicRunId(String txId, String dbName, String collName, String triggerName,
            EventType eventType, String idOrNull) {
        final var key = String.join(String.valueOf(Globals.COLL_IDENTIFIER_SEPARATOR), txId, dbName, collName,
                triggerName, eventType.name(), idOrNull == null ? BATCH_RUN_KEY : idOrNull);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static List<AdminTriggerRunEntry> confirmStaged(String runId, Set<String> landedIds) throws Exception {
        final var remaining = new ArrayList<AdminTriggerRunEntry>();
        final var emptied = new ArrayList<String>();
        for (final var chunk : AdminOperationHelper.readTriggerRuns(recordIdsFor(runId))) {
            if (chunk.getStatus() != TriggerRunStatus.STAGED) {
                remaining.add(chunk);
                continue;
            }
            chunk.narrowTo(landedIds);
            if (chunk.isEmpty()) {
                emptied.add(chunk.get_id());
            } else {
                AdminOperationHelper.saveTriggerRun(chunk);
                remaining.add(chunk);
            }
        }
        if (!emptied.isEmpty()) {
            AdminOperationHelper.deleteTriggerRuns(emptied);
        }
        return remaining;
    }

    public static void discard(String runId) {
        if (runId == null || !isEnabled()) {
            return;
        }
        try {
            final var recordIds = recordIdsFor(runId);
            if (!recordIds.isEmpty()) {
                AdminOperationHelper.deleteTriggerRuns(recordIds);
            }
        } catch (Exception e) {
            logger.error("Failed to discard the trigger run '" + runId + "'; it may replay at the next startup", e);
        }
    }

    private static String write(TriggerRunDescriptor descriptor, String runId, Map<String, Long> priorVersions,
            String txId) {
        if (!isEnabled()) {
            return null;
        }
        final var staged = priorVersions != null;
        try {
            final var chunks = descriptor.eventType() == EventType.DELETED
                    ? documentChunks(descriptor.entries(), staged)
                    : idChunks(descriptor.entries(), staged);
            if (chunks == null) {
                logger.warning("Trigger '" + descriptor.triggerName() + "' on " + descriptor.dbName() + "|"
                        + descriptor.collName()
                        + " could not be logged durably: a document exceeds maxEntrySize. Running it without a"
                        + " durable record, so it will be lost if this node dies before it completes.");
                return null;
            }
            var written = 0;
            try {
                for (var chunkSeq = 0; chunkSeq < chunks.size(); chunkSeq++) {
                    final var chunk = chunks.get(chunkSeq);
                    final var entry = chunk.toEntry(runId, chunkSeq, currentNodeId(), descriptor);
                    if (staged) {
                        entry.stage(chunk.versionsFrom(priorVersions));
                    }
                    if (txId != null) {
                        entry.setTxId(txId);
                    }
                    AdminOperationHelper.saveTriggerRun(entry);
                    written++;
                }
            } catch (Exception e) {
                discardPartialRecord(runId, written);
                throw e;
            }
            return runId;
        } catch (Exception e) {
            logger.warning("Failed to record the pending trigger run for '" + descriptor.triggerName() + "' on "
                    + descriptor.dbName() + "|" + descriptor.collName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void discardPartialRecord(String runId, int written) {
        if (written == 0) {
            return;
        }
        final var ids = new ArrayList<String>();
        for (var chunkSeq = 0; chunkSeq < written; chunkSeq++) {
            ids.add(AdminTriggerRunEntry.buildId(runId, chunkSeq));
        }
        try {
            AdminOperationHelper.deleteTriggerRuns(ids);
        } catch (Exception e) {
            logger.error("Failed to discard the partially recorded trigger run '" + runId
                    + "'; it may replay at the next startup", e);
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

    public static Set<String> pendingRunIds() {
        final var runIds = new HashSet<String>();
        for (final var recordId : cache.getTriggerRunPkIndexes().keySet()) {
            runIds.add(AdminTriggerRunEntry.runIdOf(recordId));
        }
        return runIds;
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
        final var selfNodeId = currentNodeId();
        final var stale = new ArrayList<String>();
        for (final var entry : pending()) {
            if (isStranded(entry, selfNodeId, cutoff, deadCutoff)) {
                stale.add(entry.get_id());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        AdminOperationHelper.deleteTriggerRuns(stale);
        logger.info("Garbage-collected " + stale.size() + " stranded trigger run record(s)");
    }

    private static boolean isStranded(AdminTriggerRunEntry entry, String selfNodeId, long cutoff, long deadCutoff) {
        if (entry.getStatus() == TriggerRunStatus.DEAD) {
            return entry.getFiredAt() < deadCutoff;
        }
        return !selfNodeId.equals(entry.getNodeId()) && entry.getFiredAt() < cutoff;
    }

    public static String currentNodeId() {
        final var self = membershipService.getSelf();
        if (self != null) {
            return self.getNodeId();
        }
        return clusterConfig.isEnabled() ? membershipService.resolveNodeId() : Globals.STANDALONE_NODE_ID;
    }

    private record Chunk(List<String> ids, List<JsonObject> documents) {
        Map<String, Long> versionsFrom(Map<String, Long> priorVersions) {
            final var versions = new LinkedHashMap<String, Long>();
            for (final var id : ids) {
                versions.put(id, priorVersions.getOrDefault(id, AdminTriggerRunEntry.ABSENT_VERSION));
            }
            for (final var document : documents) {
                final var id = document.get(Globals.PK_FIELD).asJsonString().getValue();
                versions.put(id, priorVersions.getOrDefault(id, AdminTriggerRunEntry.ABSENT_VERSION));
            }
            return versions;
        }

        AdminTriggerRunEntry toEntry(String runId, long chunkSeq, String nodeId, TriggerRunDescriptor descriptor) {
            return new AdminTriggerRunEntry(runId, chunkSeq, nodeId, descriptor.dbName(), descriptor.collName(),
                    descriptor.triggerName(), descriptor.procedureName(), descriptor.eventType(),
                    descriptor.batchMode(), descriptor.actingUser(), descriptor.depth(), descriptor.firedAt(), ids,
                    documents);
        }
    }

    private static List<Chunk> idChunks(List<DbEntry> entries, boolean staged) {
        final var budget = chunkBudget();
        final var chunks = new ArrayList<Chunk>();
        var current = new ArrayList<String>();
        var currentBytes = 0L;
        for (final var entry : entries) {
            final var size = (long) entry.get_id().length() + 4L + stagedOverhead(entry, staged);
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

    private static List<Chunk> documentChunks(List<DbEntry> entries, boolean staged) {
        final var budget = chunkBudget();
        final var chunks = new ArrayList<Chunk>();
        var current = new ArrayList<JsonObject>();
        var currentBytes = 0L;
        for (final var entry : entries) {
            final var size = (long) entry.byteSize() + stagedOverhead(entry, staged);
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

    private static long stagedOverhead(DbEntry entry, boolean staged) {
        return staged ? entry.get_id().length() + STAGED_VERSION_OVERHEAD_BYTES : 0L;
    }

    private static long chunkBudget() {
        return Math.max(1L, configuration.getMaxEntrySize() - CHUNK_OVERHEAD_BYTES);
    }
}
