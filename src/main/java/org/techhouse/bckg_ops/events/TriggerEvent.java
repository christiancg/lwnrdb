package org.techhouse.bckg_ops.events;

import java.util.List;
import java.util.Objects;
import org.techhouse.data.DbEntry;

public class TriggerEvent extends CollectionScopedEvent {
    private final String triggerName;
    private final String procedureName;
    private final boolean batchMode;
    private final List<DbEntry> entries;
    private final String actingUser;
    private final int depth;
    private final long firedAt;
    private final String runId;
    private final int attempt;

    public TriggerEvent(EventType type, String dbName, String collName, String triggerName, String procedureName,
            boolean batchMode, List<DbEntry> entries, String actingUser, int depth) {
        this(type, dbName, collName, triggerName, procedureName, batchMode, entries, actingUser, depth, null);
    }

    public TriggerEvent(EventType type, String dbName, String collName, String triggerName, String procedureName,
            boolean batchMode, List<DbEntry> entries, String actingUser, int depth, String runId) {
        this(type, dbName, collName, triggerName, procedureName, batchMode, entries, actingUser, depth, runId, 1);
    }

    public TriggerEvent(EventType type, String dbName, String collName, String triggerName, String procedureName,
            boolean batchMode, List<DbEntry> entries, String actingUser, int depth, String runId, int attempt) {
        super(type, dbName, collName);
        this.triggerName = triggerName;
        this.procedureName = procedureName;
        this.batchMode = batchMode;
        this.entries = entries;
        this.actingUser = actingUser;
        this.depth = depth;
        this.firedAt = System.currentTimeMillis();
        this.runId = runId;
        this.attempt = attempt;
    }

    public int getAttempt() {
        return attempt;
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
        return batchMode == that.batchMode && depth == that.depth && Objects.equals(triggerName, that.triggerName)
                && Objects.equals(procedureName, that.procedureName) && Objects.equals(entries, that.entries)
                && Objects.equals(actingUser, that.actingUser) && Objects.equals(runId, that.runId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), triggerName, procedureName, batchMode, entries, actingUser, depth, runId);
    }

    @Override
    public String toString() {
        return "TriggerEvent(super=" + super.toString() + ", dbName=" + getDbName() + ", collName=" + getCollName()
                + ", triggerName=" + triggerName + ", procedureName=" + procedureName + ", batchMode=" + batchMode
                + ", entries=" + entries.size() + ", actingUser=" + actingUser + ", depth=" + depth + ", runId=" + runId
                + ")";
    }
}
