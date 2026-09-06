package org.techhouse.data;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

/**
 * A scheduled procedure, persisted as one file per schedule in {@code {database}/.schedules/{name}.json}.
 * The database is the file's location rather than a field, following {@link ProcedureDefinition}, and the
 * JSON mapping is hand-written for the same reason: an absent field reads as a documented default, so a
 * record written by an older version loads unchanged.
 *
 * <p>
 * Exactly one of {@code cron} and {@code intervalMs} is set. Because a schedule is a separate record from
 * the procedure it names, one procedure can carry several schedules with different arguments.
 */
public class ScheduleDefinition {
    private static final String NAME_FIELD = "name";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String CRON_FIELD = "cron";
    private static final String INTERVAL_MS_FIELD = "intervalMs";
    private static final String ARGS_FIELD = "args";
    private static final String TIMEOUT_MS_FIELD = "timeoutMs";
    private static final String ENABLED_FIELD = "enabled";
    private static final String DEFINER_FIELD = "definer";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String VERSION_FIELD = "version";
    private static final String CREATED_AT_FIELD = "createdAt";
    private static final String UPDATED_AT_FIELD = "updatedAt";
    private static final String UPDATED_BY_FIELD = "updatedBy";

    private String name;
    private String procedureName;
    private String cron;
    private long intervalMs;
    private JsonObject args;
    private long timeoutMs;
    private boolean enabled;
    private String definer;
    private String description;
    private long version;
    private long createdAt;
    private long updatedAt;
    private String updatedBy;

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
        result.name = stringOrNull(object, NAME_FIELD);
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        result.cron = stringOrNull(object, CRON_FIELD);
        result.intervalMs = longOrZero(object, INTERVAL_MS_FIELD);
        result.args = object.has(ARGS_FIELD) && object.get(ARGS_FIELD).isJsonObject()
                ? object.get(ARGS_FIELD).asJsonObject()
                : new JsonObject();
        // Absent reads as zero, which the dispatcher reads as "use the configured scheduleTimeoutMs".
        result.timeoutMs = longOrZero(object, TIMEOUT_MS_FIELD);
        // Absent reads as enabled: a record written before the flag existed did fire.
        result.enabled = !object.has(ENABLED_FIELD) || object.get(ENABLED_FIELD).isJsonNull()
                || object.get(ENABLED_FIELD).asJsonBoolean().getValue();
        result.definer = stringOrNull(object, DEFINER_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
        result.version = longOrZero(object, VERSION_FIELD);
        result.createdAt = longOrZero(object, CREATED_AT_FIELD);
        result.updatedAt = longOrZero(object, UPDATED_AT_FIELD);
        result.updatedBy = stringOrNull(object, UPDATED_BY_FIELD);
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
        json.add(CREATED_AT_FIELD, new JsonNumber(createdAt));
        json.add(UPDATED_AT_FIELD, new JsonNumber(updatedAt));
        if (updatedBy != null) {
            json.add(UPDATED_BY_FIELD, new JsonString(updatedBy));
        }
        return json;
    }

    // The metadata a LIST_SCHEDULES response carries: the arguments can be arbitrarily large and are not
    // what a listing is for.
    public JsonObject toSummaryJson() {
        final var json = toJsonObject();
        json.remove(ARGS_FIELD);
        return json;
    }

    private static String stringOrNull(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).asJsonString().getValue();
    }

    private static long longOrZero(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return 0L;
        }
        return object.get(field).asJsonNumber().getValue().longValue();
    }

    public String getName() {
        return name;
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

    public boolean isEnabled() {
        return enabled;
    }

    public String getDefiner() {
        return definer;
    }

    public String getDescription() {
        return description;
    }

    public long getVersion() {
        return version;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
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
