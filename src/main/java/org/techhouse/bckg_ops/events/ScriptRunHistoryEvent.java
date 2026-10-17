package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.ops.ScriptRunRecord;

public class ScriptRunHistoryEvent extends Event {
    private final ScriptRunRecord record;
    private final int retries;

    public ScriptRunHistoryEvent(ScriptRunRecord record) {
        this(record, 0);
    }

    public ScriptRunHistoryEvent(ScriptRunRecord record, int retries) {
        super(EventType.CREATED);
        this.record = record;
        this.retries = retries;
    }

    public ScriptRunRecord getRecord() {
        return record;
    }

    public int getRetries() {
        return retries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ScriptRunHistoryEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return retries == that.retries && Objects.equals(record, that.record);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), record, retries);
    }

    @Override
    public String toString() {
        return "ScriptRunHistoryEvent(super=" + super.toString() + ", runId=" + (record == null ? null : record.runId())
                + ", retries=" + retries + ")";
    }
}
