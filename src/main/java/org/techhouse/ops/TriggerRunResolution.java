package org.techhouse.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.cluster.msg.TriggerRunRow;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.req.ResolveTriggerRunRequest;

public final class TriggerRunResolution {
    private static final Logger logger = Logger.logFor(TriggerRunResolution.class);
    private static final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);

    private TriggerRunResolution() {
    }

    public static List<TriggerRunRow> localRows(TriggerRunStatus filter) {
        if (!TriggerRunLog.isEnabled()) {
            return List.of();
        }
        final var rows = new ArrayList<TriggerRunRow>();
        try {
            for (final var chunks : byRun().values()) {
                final var first = chunks.getFirst();
                if (filter != null && first.getStatus() != filter) {
                    continue;
                }
                rows.add(new TriggerRunRow(first.getRunId(), first.getStatus().name(), first.getDbName(),
                        first.getCollName(), first.getTriggerName(), first.getProcedureName(),
                        first.getEventType().name(), first.getAttempts(), first.getLastError(), first.getFiredAt(),
                        first.getNextAttemptAt()));
            }
        } catch (Exception e) {
            logger.warning("Failed to read the recorded trigger runs: " + e.getMessage());
        }
        return rows;
    }

    public static boolean resolveLocal(String runId, String decision) {
        if (runId == null || !TriggerRunLog.isEnabled()) {
            return false;
        }
        try {
            final var chunks = byRun().get(runId);
            if (chunks == null) {
                return false;
            }
            if (ResolveTriggerRunRequest.DECISION_DISCARD.equals(decision)) {
                TriggerDispatcher.consumeQuietly(runId, chunks.getFirst().getTriggerName());
                logger.info("Discarded trigger run '" + runId + "' on operator request");
                return true;
            }
            if (!ResolveTriggerRunRequest.DECISION_REPLAY.equals(decision)) {
                logger.warning("Ignoring trigger run '" + runId + "': unknown decision '" + decision + "'");
                return false;
            }
            final var event = TriggerRunRecovery.toEvent(chunks);
            if (event == null) {
                TriggerDispatcher.consumeQuietly(runId, chunks.getFirst().getTriggerName());
                logger.info("Discarded trigger run '" + runId + "': the documents it applied to are gone");
                return true;
            }
            TriggerRunLog.markAttempt(runId, TriggerRunStatus.PENDING, 0, null, 0L);
            triggerExecutor.submit(event);
            logger.info("Re-queued trigger run '" + runId + "' on operator request");
            return true;
        } catch (Exception e) {
            logger.warning("Failed to resolve trigger run '" + runId + "': " + e.getMessage());
            return false;
        }
    }

    private static LinkedHashMap<String, List<AdminTriggerRunEntry>> byRun() throws Exception {
        final var grouped = new LinkedHashMap<String, List<AdminTriggerRunEntry>>();
        for (final var entry : TriggerRunLog.pending()) {
            grouped.computeIfAbsent(entry.getRunId(), _ -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }
}
