package org.techhouse.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

/**
 * A trigger on one collection, persisted with every other trigger on that collection in
 * {@code {db}/{coll}/{coll}-triggers.json} beside the collection's schema. The database and collection are
 * the file's location rather than fields, so a record cannot disagree with where it lives, and a
 * DROP_COLLECTION removes it with the data.
 *
 * <p>
 * {@code events} reuses the {@link EventType} the write path already emits, so a trigger's filter and the
 * event that fires it cannot drift apart. {@code definer} is the user whose authority a run has.
 */
public class TriggerDefinition {
    public static final String MODE_DOCUMENT = "document";
    public static final String MODE_BATCH = "batch";

    private static final String TRIGGERS_FIELD = "triggers";
    private static final String NAME_FIELD = "name";
    private static final String EVENTS_FIELD = "events";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String MODE_FIELD = "mode";
    private static final String ALLOW_CASCADE_FIELD = "allowCascade";
    private static final String ENABLED_FIELD = "enabled";
    private static final String DEFINER_FIELD = "definer";
    private static final String VERSION_FIELD = "version";
    private static final String CREATED_AT_FIELD = "createdAt";
    private static final String UPDATED_AT_FIELD = "updatedAt";
    private static final String UPDATED_BY_FIELD = "updatedBy";

    private String name;
    private Set<EventType> events;
    private String procedureName;
    private String mode;
    private boolean allowCascade;
    private boolean enabled;
    private String definer;
    private long version;
    private long createdAt;
    private long updatedAt;
    private String updatedBy;

    public TriggerDefinition() {
        this.events = new LinkedHashSet<>();
        this.mode = MODE_DOCUMENT;
    }

    public TriggerDefinition(String name, Set<EventType> events, String procedureName, String mode,
            boolean allowCascade, boolean enabled, String definer, long version, long createdAt, long updatedAt,
            String updatedBy) {
        this.name = name;
        this.events = events == null ? new LinkedHashSet<>() : events;
        this.procedureName = procedureName;
        this.mode = mode == null ? MODE_DOCUMENT : mode;
        this.allowCascade = allowCascade;
        this.enabled = enabled;
        this.definer = definer;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public static TriggerDefinition fromJsonObject(JsonObject object) {
        final var result = new TriggerDefinition();
        result.name = stringOrNull(object, NAME_FIELD);
        result.events = new LinkedHashSet<>();
        if (object.has(EVENTS_FIELD) && object.get(EVENTS_FIELD).isJsonArray()) {
            for (final var element : object.get(EVENTS_FIELD).asJsonArray().asList()) {
                result.events.add(EventType.valueOf(element.asJsonString().getValue()));
            }
        }
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        final var mode = stringOrNull(object, MODE_FIELD);
        result.mode = mode == null ? MODE_DOCUMENT : mode;
        result.allowCascade = booleanOrDefault(object, ALLOW_CASCADE_FIELD, false);
        result.enabled = booleanOrDefault(object, ENABLED_FIELD, true);
        result.definer = stringOrNull(object, DEFINER_FIELD);
        result.version = longOrZero(object, VERSION_FIELD);
        result.createdAt = longOrZero(object, CREATED_AT_FIELD);
        result.updatedAt = longOrZero(object, UPDATED_AT_FIELD);
        result.updatedBy = stringOrNull(object, UPDATED_BY_FIELD);
        return result;
    }

    public JsonObject toJsonObject() {
        final var json = new JsonObject();
        json.add(NAME_FIELD, new JsonString(name));
        final var eventsArray = new JsonArray();
        events.forEach(event -> eventsArray.add(new JsonString(event.name())));
        json.add(EVENTS_FIELD, eventsArray);
        json.add(PROCEDURE_NAME_FIELD, new JsonString(procedureName));
        json.add(MODE_FIELD, new JsonString(mode));
        json.add(ALLOW_CASCADE_FIELD, new JsonBoolean(allowCascade));
        json.add(ENABLED_FIELD, new JsonBoolean(enabled));
        if (definer != null) {
            json.add(DEFINER_FIELD, new JsonString(definer));
        }
        json.add(VERSION_FIELD, new JsonNumber(version));
        json.add(CREATED_AT_FIELD, new JsonNumber(createdAt));
        json.add(UPDATED_AT_FIELD, new JsonNumber(updatedAt));
        if (updatedBy != null) {
            json.add(UPDATED_BY_FIELD, new JsonString(updatedBy));
        }
        return json;
    }

    // The on-disk shape is an object wrapping the list rather than a bare array: EJson's reader parses a
    // top-level object, not a top-level array, and the wrapper leaves room for file-level metadata later.
    public static JsonObject toFileJson(List<TriggerDefinition> definitions) {
        final var array = new JsonArray();
        definitions.forEach(definition -> array.add(definition.toJsonObject()));
        final var file = new JsonObject();
        file.add(TRIGGERS_FIELD, array);
        return file;
    }

    public static List<TriggerDefinition> fromFileJson(JsonObject file) {
        final var result = new ArrayList<TriggerDefinition>();
        if (file.has(TRIGGERS_FIELD) && file.get(TRIGGERS_FIELD).isJsonArray()) {
            for (final var element : file.get(TRIGGERS_FIELD).asJsonArray().asList()) {
                result.add(fromJsonObject(element.asJsonObject()));
            }
        }
        return result;
    }

    public static List<TriggerDefinition> fromJsonArray(JsonArray array) {
        final var result = new ArrayList<TriggerDefinition>();
        for (final var element : array.asList()) {
            result.add(fromJsonObject(element.asJsonObject()));
        }
        return result;
    }

    public static JsonArray toJsonArray(List<TriggerDefinition> definitions) {
        final var array = new JsonArray();
        definitions.forEach(definition -> array.add(definition.toJsonObject()));
        return array;
    }

    private static String stringOrNull(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).asJsonString().getValue();
    }

    private static boolean booleanOrDefault(JsonObject object, String field, boolean fallback) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        return object.get(field).asJsonBoolean().getValue();
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

    public Set<EventType> getEvents() {
        return events;
    }

    public String getProcedureName() {
        return procedureName;
    }

    public String getMode() {
        return mode;
    }

    public boolean isBatchMode() {
        return MODE_BATCH.equals(mode);
    }

    public boolean isAllowCascade() {
        return allowCascade;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDefiner() {
        return definer;
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
        if (!(o instanceof TriggerDefinition that))
            return false;
        return allowCascade == that.allowCascade && enabled == that.enabled && version == that.version
                && createdAt == that.createdAt && updatedAt == that.updatedAt && Objects.equals(name, that.name)
                && Objects.equals(events, that.events) && Objects.equals(procedureName, that.procedureName)
                && Objects.equals(mode, that.mode) && Objects.equals(definer, that.definer)
                && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, events, procedureName, mode, allowCascade, enabled, definer, version, createdAt,
                updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "TriggerDefinition(name=" + name + ", events=" + events + ", procedureName=" + procedureName + ", mode="
                + mode + ", allowCascade=" + allowCascade + ", enabled=" + enabled + ", definer=" + definer
                + ", version=" + version + ")";
    }
}
