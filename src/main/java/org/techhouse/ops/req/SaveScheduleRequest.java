package org.techhouse.ops.req;

import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationType;

public class SaveScheduleRequest extends VersionedDefinitionRequest {
    private String name;
    private String procedureName;
    private String cron;
    private long intervalMs;
    private JsonObject args;
    private long timeoutMs;
    private String description;
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

    public String getStampedDefiner() {
        return stampedDefiner;
    }

    public void setStampedDefiner(String stampedDefiner) {
        this.stampedDefiner = stampedDefiner;
    }
}
