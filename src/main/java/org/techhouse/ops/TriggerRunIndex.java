package org.techhouse.ops;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.cache.Cache;
import org.techhouse.data.NameMemo;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ioc.IocContainer;

public final class TriggerRunIndex {
    static final int READ_CHUNK = 1024;
    private static final int NAME_MEMO_CAPACITY = 8192;
    private static final String NAMES = "";
    private static final Cache cache = IocContainer.get(Cache.class);
    private static final Map<String, Summary> summaries = new ConcurrentHashMap<>();
    private static final NameMemo<String> names = new NameMemo<>(NAME_MEMO_CAPACITY);

    public record Summary(PkIndexEntry row, String nodeId, TriggerRunStatus status, long firedAt, long lastErrorAt,
            String dbName, String collName, String txId) {
        public String recordId() {
            return row.getValue();
        }

        public String runId() {
            return AdminTriggerRunEntry.runIdOf(row.getValue());
        }

        public long diedAt() {
            return Math.max(firedAt, lastErrorAt);
        }

        public boolean isDead() {
            return status == TriggerRunStatus.DEAD;
        }
    }

    private TriggerRunIndex() {
    }

    public static void learn(AdminTriggerRunEntry entry, PkIndexEntry row) {
        if (row != null) {
            summaries.put(row.getValue(), summaryOf(entry, row));
        }
    }

    public static void forget(String recordId) {
        summaries.remove(recordId);
    }

    public static List<Summary> current() throws IOException, InterruptedException {
        final var rows = cache.getTriggerRunPkIndexes();
        final var known = new ArrayList<Summary>(rows.size());
        final var unknown = new ArrayList<String>();
        for (final var row : rows.entrySet()) {
            final var summary = summaries.get(row.getKey());
            if (summary != null && summary.row() == row.getValue()) {
                known.add(summary);
            } else {
                unknown.add(row.getKey());
            }
        }
        for (var from = 0; from < unknown.size(); from += READ_CHUNK) {
            known.addAll(readChunk(unknown.subList(from, Math.min(unknown.size(), from + READ_CHUNK))));
        }
        if (summaries.size() > rows.size()) {
            summaries.keySet().removeIf(recordId -> !rows.containsKey(recordId));
        }
        return known;
    }

    public static int knownDeadIn(String dbName) {
        var dead = 0;
        for (final var summary : summaries.values()) {
            if (summary.isDead() && dbName.equals(summary.dbName())) {
                dead++;
            }
        }
        return dead;
    }

    private static List<Summary> readChunk(List<String> recordIds) throws IOException, InterruptedException {
        final var before = new HashMap<String, PkIndexEntry>(recordIds.size() * 2);
        for (final var recordId : recordIds) {
            final var row = cache.getPkIndexTriggerRun(recordId);
            if (row != null) {
                before.put(recordId, row);
            }
        }
        final var read = new ArrayList<Summary>(recordIds.size());
        for (final var entry : AdminOperationHelper.readTriggerRuns(recordIds)) {
            final var row = before.get(entry.get_id());
            if (row == null) {
                continue;
            }
            final var summary = summaryOf(entry, row);
            summaries.compute(row.getValue(), (_, held) -> isCurrent(held) ? held : summary);
            read.add(summary);
        }
        return read;
    }

    private static boolean isCurrent(Summary summary) {
        return summary != null && cache.getPkIndexTriggerRun(summary.recordId()) == summary.row();
    }

    private static Summary summaryOf(AdminTriggerRunEntry entry, PkIndexEntry row) {
        return new Summary(row, canonical(entry.getNodeId()), entry.getStatus(), entry.getFiredAt(),
                entry.getLastErrorAt(), canonical(entry.getDbName()), canonical(entry.getCollName()), entry.getTxId());
    }

    private static String canonical(String name) {
        return name == null ? null : names.remember(NAMES, name, name);
    }
}
