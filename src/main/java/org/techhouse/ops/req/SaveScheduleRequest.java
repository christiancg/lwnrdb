package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class SaveScheduleRequest extends OperationRequest {
    private String name;
    private String procedureName;
    private String cron;
    private long intervalMs;
    private JsonObject args;
    private long timeoutMs;
    private String description;
    private Boolean enabled;
    private Long ifVersion;
    // Stamped by the coordinator during local execution so a peer re-executing this request writes a
    // byte-identical file. Never supplied by a client - the handler overwrites whatever arrived.
    private long stampedVersion;
    private long stampedUpdatedAt;
    private String stampedUpdatedBy;
    private String stampedDefiner;

    public SaveScheduleRequest() {
        super(OperationType.SAVE_SCHEDULE, null, null);
    }

    public SaveScheduleRequest(String databaseName, String name, String procedureName) {
        super(OperationType.SAVE_SCHEDULE, databaseName, null);
        this.name = name;
        this.procedureName = procedureName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public JsonObject getArgs() {
        return args == null ? new JsonObject() : args;
    }

    public void setArgs(JsonObject args) {
        this.args = args;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
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

    public String getStampedDefiner() {
        return stampedDefiner;
    }

    public void setStampedDefiner(String stampedDefiner) {
        this.stampedDefiner = stampedDefiner;
    }
}
