package org.techhouse.data;

import static org.techhouse.data.JsonFieldReader.longOrZero;
import static org.techhouse.data.JsonFieldReader.stringOrNull;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public class ScheduleDefinition extends StoredDefinition {
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String CRON_FIELD = "cron";
    private static final String INTERVAL_MS_FIELD = "intervalMs";
    private static final String ARGS_FIELD = "args";
    private static final String TIMEOUT_MS_FIELD = "timeoutMs";
    private static final String DEFINER_FIELD = "definer";
    private static final String DESCRIPTION_FIELD = "description";

    private String procedureName;
    private String cron;
    private long intervalMs;
    private JsonObject args;
    private long timeoutMs;
    private String definer;
    private String description;

    public ScheduleDefinition() {
    }

    public ScheduleDefinition(String name, String procedureName, String cron, long intervalMs, JsonObject args,
            long timeoutMs, boolean enabled, String definer, String description, long version, long createdAt,
            long updatedAt, String updatedBy) {
        this.name = name;
        this.procedureName = procedureName;
        this.cron = cron;
        this.intervalMs = intervalMs;
        this.args = args == null ? new JsonObject() : args;
        this.timeoutMs = timeoutMs;
        this.enabled = enabled;
        this.definer = definer;
        this.description = description;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static ScheduleDefinition fromJsonObject(JsonObject object) {
        final var result = new ScheduleDefinition();
        result.readCommonFields(object);
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        result.cron = stringOrNull(object, CRON_FIELD);
        result.intervalMs = longOrZero(object, INTERVAL_MS_FIELD);
        result.args = object.has(ARGS_FIELD) && object.get(ARGS_FIELD).isJsonObject()
                ? object.get(ARGS_FIELD).asJsonObject()
                : new JsonObject();
        result.timeoutMs = longOrZero(object, TIMEOUT_MS_FIELD);
        result.definer = stringOrNull(object, DEFINER_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
        return result;
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        json.add(NAME_FIELD, new JsonString(name));
        json.add(PROCEDURE_NAME_FIELD, new JsonString(procedureName));
        if (cron != null) {
            json.add(CRON_FIELD, new JsonString(cron));
        }
        json.add(INTERVAL_MS_FIELD, new JsonNumber(intervalMs));
        json.add(ARGS_FIELD, args == null ? new JsonObject() : args);
        json.add(TIMEOUT_MS_FIELD, new JsonNumber(timeoutMs));
        json.add(ENABLED_FIELD, new JsonBoolean(enabled));
        if (definer != null) {
            json.add(DEFINER_FIELD, new JsonString(definer));
        }
        if (description != null) {
            json.add(DESCRIPTION_FIELD, new JsonString(description));
        }
        json.add(VERSION_FIELD, new JsonNumber(version));
        writeAuditFields(json);
        return json;
    }

    public JsonObject toSummaryJson() {
        final var json = toJsonObject();
        json.remove(ARGS_FIELD);
        return json;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public String getCron() {
        return cron;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public JsonObject getArgs() {
        return args == null ? new JsonObject() : args;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public String getDefiner() {
        return definer;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof ScheduleDefinition that))
            return false;
        return intervalMs == that.intervalMs && timeoutMs == that.timeoutMs && enabled == that.enabled
                && version == that.version && createdAt == that.createdAt && updatedAt == that.updatedAt
                && Objects.equals(name, that.name) && Objects.equals(procedureName, that.procedureName)
                && Objects.equals(cron, that.cron) && Objects.equals(getArgs(), that.getArgs())
                && Objects.equals(definer, that.definer) && Objects.equals(description, that.description)
                && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, procedureName, cron, intervalMs, getArgs(), timeoutMs, enabled, definer, description,
                version, createdAt, updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "ScheduleDefinition(name=" + name + ", procedure=" + procedureName + ", cron=" + cron + ", intervalMs="
                + intervalMs + ", enabled=" + enabled + ", definer=" + definer + ", version=" + version + ")";
    }
}
