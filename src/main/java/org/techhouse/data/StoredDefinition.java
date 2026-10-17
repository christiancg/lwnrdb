package org.techhouse.data;

import static org.techhouse.data.JsonFieldReader.booleanOrDefault;
import static org.techhouse.data.JsonFieldReader.longOrZero;
import static org.techhouse.data.JsonFieldReader.stringOrNull;

import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public abstract class StoredDefinition {
    protected static final String NAME_FIELD = "name";
    protected static final String ENABLED_FIELD = "enabled";
    protected static final String VERSION_FIELD = "version";
    protected static final String CREATED_AT_FIELD = "createdAt";
    protected static final String UPDATED_AT_FIELD = "updatedAt";
    protected static final String UPDATED_BY_FIELD = "updatedBy";
    protected static final String DISABLED_REASON_FIELD = "disabledReason";

    protected String name;
    protected boolean enabled;
    protected long version;
    protected long createdAt;
    protected long updatedAt;
    protected String updatedBy;
    protected String disabledReason;

    protected void readCommonFields(JsonObject object) {
        name = stringOrNull(object, NAME_FIELD);
        enabled = booleanOrDefault(object, ENABLED_FIELD, true);
        version = longOrZero(object, VERSION_FIELD);
        createdAt = longOrZero(object, CREATED_AT_FIELD);
        updatedAt = longOrZero(object, UPDATED_AT_FIELD);
        updatedBy = stringOrNull(object, UPDATED_BY_FIELD);
        disabledReason = stringOrNull(object, DISABLED_REASON_FIELD);
    }

    protected void writeAuditFields(JsonObject json) {
        json.add(CREATED_AT_FIELD, new JsonNumber(createdAt));
        json.add(UPDATED_AT_FIELD, new JsonNumber(updatedAt));
        if (updatedBy != null) {
            json.add(UPDATED_BY_FIELD, new JsonString(updatedBy));
        }
        if (disabledReason != null) {
            json.add(DISABLED_REASON_FIELD, new JsonString(disabledReason));
        }
    }

    protected void markDisabled(String reason, long at) {
        enabled = false;
        disabledReason = reason;
        version = version + 1;
        updatedAt = at;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
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

    public String getDisabledReason() {
        return disabledReason;
    }
}
