package org.techhouse.bckg_ops.events;

import java.util.Objects;
import org.techhouse.ops.ScriptRunRecord;

public class ScriptRunHistoryEvent extends Event {
    private final ScriptRunRecord record;

    public ScriptRunHistoryEvent(ScriptRunRecord record) {
        super(EventType.CREATED);
        this.record = record;
    }

    public ScriptRunRecord getRecord() {
        return record;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ScriptRunHistoryEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return Objects.equals(record, that.record);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), record);
    }

    @Override
    public String toString() {
        return "ScriptRunHistoryEvent(super=" + super.toString() + ", runId=" + (record == null ? null : record.runId())
                + ")";
    }
}
