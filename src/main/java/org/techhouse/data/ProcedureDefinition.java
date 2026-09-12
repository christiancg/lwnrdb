package org.techhouse.data;

import static org.techhouse.data.JsonFieldReader.stringOrNull;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class ProcedureDefinition extends StoredDefinition {
    private static final String SOURCE_FIELD = "source";
    private static final String SOURCE_HASH_FIELD = "sourceHash";
    private static final String DESCRIPTION_FIELD = "description";

    private String source;
    private String sourceHash;
    private String description;

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
        result.readCommonFields(object);
        result.source = stringOrNull(object, SOURCE_FIELD);
        result.sourceHash = stringOrNull(object, SOURCE_HASH_FIELD);
        result.description = stringOrNull(object, DESCRIPTION_FIELD);
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
        writeAuditFields(json);
        return json;
    }

    public JsonObject toSummaryJson() {
        final var json = toJsonObject();
        json.remove(SOURCE_FIELD);
        return json;
    }

    public String getSource() {
        return source;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public String getDescription() {
        return description;
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
