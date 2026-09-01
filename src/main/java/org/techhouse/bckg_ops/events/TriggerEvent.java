package org.techhouse.bckg_ops.events;

import java.util.List;
import java.util.Objects;
import org.techhouse.data.DbEntry;

/**
 * One trigger firing, queued by {@code ops.TriggerHelper} after a write committed and dispatched by
 * {@code ops.TriggerDispatcher}. Carries both identities: {@code actingUser} is who performed the write
 * (what the trigger's args report and what explains why it fired), while the authority the script runs
 * under comes from the trigger record's definer.
 */
public class TriggerEvent extends Event {
    private final String dbName;
    private final String collName;
    private final String triggerName;
    private final String procedureName;
    private final boolean batchMode;
    private final List<DbEntry> entries;
    private final String actingUser;
    private final int depth;
    private final long firedAt;
    private final String runId;

    public TriggerEvent(EventType type, String dbName, String collName, String triggerName, String procedureName,
            boolean batchMode, List<DbEntry> entries, String actingUser, int depth) {
        this(type, dbName, collName, triggerName, procedureName, batchMode, entries, actingUser, depth, null);
    }

    public TriggerEvent(EventType type, String dbName, String collName, String triggerName, String procedureName,
            boolean batchMode, List<DbEntry> entries, String actingUser, int depth, String runId) {
        super(type);
        this.dbName = dbName;
        this.collName = collName;
        this.triggerName = triggerName;
        this.procedureName = procedureName;
        this.batchMode = batchMode;
        this.entries = entries;
        this.actingUser = actingUser;
        this.depth = depth;
        this.firedAt = System.currentTimeMillis();
        this.runId = runId;
    }

    public String getDbName() {
        return dbName;
    }

    public String getCollName() {
        return collName;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public boolean isBatchMode() {
        return batchMode;
    }

    public List<DbEntry> getEntries() {
        return entries;
    }

    public String getActingUser() {
        return actingUser;
    }

    public int getDepth() {
        return depth;
    }

    public long getFiredAt() {
        return firedAt;
    }

    public String getRunId() {
        return runId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof TriggerEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return batchMode == that.batchMode && depth == that.depth && Objects.equals(dbName, that.dbName)
                && Objects.equals(collName, that.collName) && Objects.equals(triggerName, that.triggerName)
                && Objects.equals(procedureName, that.procedureName) && Objects.equals(entries, that.entries)
                && Objects.equals(actingUser, that.actingUser) && Objects.equals(runId, that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dbName, collName, triggerName, procedureName, batchMode, entries,
                actingUser, depth, runId);
    }

    @Override
    public String toString() {
        return "TriggerEvent(super=" + super.toString() + ", dbName=" + dbName + ", collName=" + collName
                + ", triggerName=" + triggerName + ", procedureName=" + procedureName + ", batchMode=" + batchMode
                + ", entries=" + entries.size() + ", actingUser=" + actingUser + ", depth=" + depth + ", runId=" + runId
                + ")";
    }
}
