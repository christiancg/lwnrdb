package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;

public class SaveTriggerRequest extends OperationRequest {
    private String name;
    private List<String> events;
    private String procedureName;
    private String mode;
    private Boolean allowCascade;
    private Boolean enabled;
    private Long ifVersion;
    // Stamped by the coordinator during local execution so a peer re-executing this request writes a
    // byte-identical file. The definer especially must be stamped: a peer has no acting user of its own,
    // and two nodes disagreeing about it would run the same write under different authority.
    private long stampedVersion;
    private long stampedUpdatedAt;
    private String stampedUpdatedBy;
    private String stampedDefiner;

    public SaveTriggerRequest() {
        super(OperationType.SAVE_TRIGGER, null, null);
    }

    public SaveTriggerRequest(String databaseName, String collectionName, String name, List<String> events,
            String procedureName) {
        super(OperationType.SAVE_TRIGGER, databaseName, collectionName);
        this.name = name;
        this.events = events;
        this.procedureName = procedureName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getEvents() {
        return events == null ? List.of() : events;
    }

    public void setEvents(List<String> events) {
        this.events = events;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isAllowCascade() {
        return allowCascade != null && allowCascade;
    }

    public void setAllowCascade(Boolean allowCascade) {
        this.allowCascade = allowCascade;
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Long getIfVersion() {
        return ifVersion;
    }

    public void setIfVersion(Long ifVersion) {
        this.ifVersion = ifVersion;
    }

    public long getStampedVersion() {
        return stampedVersion;
    }

    public void setStampedVersion(long stampedVersion) {
        this.stampedVersion = stampedVersion;
    }

    public long getStampedUpdatedAt() {
        return stampedUpdatedAt;
    }

    public void setStampedUpdatedAt(long stampedUpdatedAt) {
        this.stampedUpdatedAt = stampedUpdatedAt;
    }

    public String getStampedUpdatedBy() {
        return stampedUpdatedBy;
    }

    public void setStampedUpdatedBy(String stampedUpdatedBy) {
        this.stampedUpdatedBy = stampedUpdatedBy;
    }

    public String getStampedDefiner() {
        return stampedDefiner;
    }

    public void setStampedDefiner(String stampedDefiner) {
        this.stampedDefiner = stampedDefiner;
    }
}
