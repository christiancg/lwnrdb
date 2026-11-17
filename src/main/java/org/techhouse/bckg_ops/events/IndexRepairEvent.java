package org.techhouse.bckg_ops.events;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class IndexRepairEvent extends CollectionScopedEvent {
    private final List<String> ids;
    private final Set<String> repairedFields;
    private final String resumeField;
    private final int resumeAt;

    public IndexRepairEvent(String dbName, String collName, List<String> ids, long incarnation,
            long pendingGeneration) {
        this(dbName, collName, ids, incarnation, pendingGeneration, Set.of(), null, 0);
    }

    public IndexRepairEvent(String dbName, String collName, List<String> ids, long incarnation, long pendingGeneration,
            Set<String> repairedFields, String resumeField, int resumeAt) {
        super(EventType.UPDATED, dbName, collName, incarnation, pendingGeneration);
        this.ids = List.copyOf(ids);
        this.repairedFields = Set.copyOf(repairedFields);
        this.resumeField = resumeField;
        this.resumeAt = resumeAt;
    }

    public List<String> getIds() {
        return ids;
    }

    public Set<String> getRepairedFields() {
        return repairedFields;
    }

    public String getResumeField() {
        return resumeField;
    }

    public int getResumeAt() {
        return resumeAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IndexRepairEvent that))
            return false;
        if (!super.equals(o))
            return false;
        return resumeAt == that.resumeAt && Objects.equals(ids, that.ids)
                && Objects.equals(repairedFields, that.repairedFields) && Objects.equals(resumeField, that.resumeField);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), ids, repairedFields, resumeField, resumeAt);
    }

    @Override
    public String toString() {
        return "IndexRepairEvent(dbName=" + getDbName() + ", collName=" + getCollName() + ", incarnation="
                + getIncarnation() + ", pendingGeneration=" + getPendingGeneration() + ", ids=" + ids.size()
                + ", repairedFields=" + repairedFields + ", resumeField=" + resumeField + ", resumeAt=" + resumeAt
                + ")";
    }
}
