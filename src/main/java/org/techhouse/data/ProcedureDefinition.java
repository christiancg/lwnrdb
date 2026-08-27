package org.techhouse.data;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

/**
 * A stored procedure, persisted as one file per procedure in {@code {database}/.procedures/{name}.json}. The
 * database is the file's location rather than a field, so a record can never disagree with where it lives.
 * The JSON mapping is hand-written (rather than left to the EJson reflection serializer) so an absent field
 * reads as a documented default, which is what lets a record written by an older version load unchanged.
 */
public class ProcedureDefinition {
    private static final String NAME_FIELD = "name";
    private static final String SOURCE_FIELD = "source";
    private static final String SOURCE_HASH_FIELD = "sourceHash";
    private static final String VERSION_FIELD = "version";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String ENABLED_FIELD = "enabled";
    private static final String CREATED_AT_FIELD = "createdAt";
    private static final String UPDATED_AT_FIELD = "updatedAt";
    private static final String UPDATED_BY_FIELD = "updatedBy";

    private String name;
    private String source;
    private String sourceHash;
    private long version;
    private String description;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;
    private String updatedBy;

    public ProcedureDefinition() {
    }

    public ProcedureDefinition(String name, String source, long version, String description, boolean enabled,
            long createdAt, long updatedAt, String updatedBy) {
        this.name = name;
        this.source = source;
        this.sourceHash = JsonUtils.sha256(source);
        this.version = version;
        this.description = description;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static ProcedureDefinition fromJsonObject(JsonObject object) {
        final var result = new ProcedureDefinition();
        result.name = stringOrNull(object, NAME_FIELD);
        result.source = stringOrNull(object, SOURCE_FIELD);
        result.sourceHash = stringOrNull(object, SOURCE_HASH_FIELD);
        result.version = longOrZero(object, VERSION_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
        // Absent reads as enabled: a record written before the flag existed was callable.
        result.enabled = !object.has(ENABLED_FIELD) || object.get(ENABLED_FIELD).isJsonNull()
                || object.get(ENABLED_FIELD).asJsonBoolean().getValue();
        result.createdAt = longOrZero(object, CREATED_AT_FIELD);
        result.updatedAt = longOrZero(object, UPDATED_AT_FIELD);
        result.updatedBy = stringOrNull(object, UPDATED_BY_FIELD);
        return result;
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        json.add(NAME_FIELD, new JsonString(name));
        json.add(SOURCE_FIELD, new JsonString(source));
        json.add(SOURCE_HASH_FIELD, new JsonString(sourceHash));
        json.add(VERSION_FIELD, new JsonNumber(version));
        if (description != null) {
            json.add(DESCRIPTION_FIELD, new JsonString(description));
        }
        json.add(ENABLED_FIELD, new JsonBoolean(enabled));
        json.add(CREATED_AT_FIELD, new JsonNumber(createdAt));
        json.add(UPDATED_AT_FIELD, new JsonNumber(updatedAt));
        if (updatedBy != null) {
            json.add(UPDATED_BY_FIELD, new JsonString(updatedBy));
        }
        return json;
    }

    // The metadata a LIST_PROCEDURES response carries when the caller did not ask for the source.
    public JsonObject toSummaryJson() {
        final var json = toJsonObject();
        json.remove(SOURCE_FIELD);
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

    public String getSource() {
        return source;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public long getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
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
        if (!(o instanceof ProcedureDefinition that))
            return false;
        return version == that.version && enabled == that.enabled && createdAt == that.createdAt
                && updatedAt == that.updatedAt && Objects.equals(name, that.name) && Objects.equals(source, that.source)
                && Objects.equals(sourceHash, that.sourceHash) && Objects.equals(description, that.description)
                && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, source, sourceHash, version, description, enabled, createdAt, updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "ProcedureDefinition(name=" + name + ", version=" + version + ", enabled=" + enabled + ", sourceHash="
                + sourceHash + ", updatedBy=" + updatedBy + ")";
    }
}
