package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public abstract class VersionedDefinitionRequest extends OperationRequest {
    private Boolean enabled;
    private Long ifVersion;
    private long stampedVersion;
    private long stampedUpdatedAt;
    private String stampedUpdatedBy;

    protected VersionedDefinitionRequest(OperationType type, String databaseName, String collectionName) {
        super(type, databaseName, collectionName);
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
}
