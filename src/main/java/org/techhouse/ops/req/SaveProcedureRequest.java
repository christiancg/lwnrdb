package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class SaveProcedureRequest extends OperationRequest {
    private String name;
    private String script;
    private String description;
    private Boolean enabled;
    private Long ifVersion;
    // Stamped by the coordinator during local execution so a peer re-executing this request writes a
    // byte-identical file. Never supplied by a client - the handler overwrites whatever arrived.
    private long stampedVersion;
    private long stampedUpdatedAt;
    private String stampedUpdatedBy;

    public SaveProcedureRequest() {
        super(OperationType.SAVE_PROCEDURE, null, null);
    }

    public SaveProcedureRequest(String databaseName, String name, String script) {
        super(OperationType.SAVE_PROCEDURE, databaseName, null);
        this.name = name;
        this.script = script;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
