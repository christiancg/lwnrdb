package org.techhouse.data;

import static org.techhouse.data.JsonFieldReader.booleanOrDefault;
import static org.techhouse.data.JsonFieldReader.stringOrNull;

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

public class TriggerDefinition extends StoredDefinition {
    public static final String MODE_DOCUMENT = "document";
    public static final String MODE_BATCH = "batch";
    public static final String TIMING_AFTER = "after";
    public static final String TIMING_BEFORE = "before";

    private static final String TRIGGERS_FIELD = "triggers";
    private static final String EVENTS_FIELD = "events";
    private static final String PROCEDURE_NAME_FIELD = "procedureName";
    private static final String MODE_FIELD = "mode";
    private static final String TIMING_FIELD = "timing";
    private static final String ALLOW_CASCADE_FIELD = "allowCascade";
    private static final String DEFINER_FIELD = "definer";

    private Set<EventType> events;
    private String procedureName;
    private String mode;
    private String timing;
    private boolean allowCascade;
    private String definer;

    public TriggerDefinition() {
        this.events = new LinkedHashSet<>();
        this.mode = MODE_DOCUMENT;
        this.timing = TIMING_AFTER;
    }

    public TriggerDefinition(String name, Set<EventType> events, String procedureName, String mode,
            boolean allowCascade, boolean enabled, String definer, long version, long createdAt, long updatedAt,
            String updatedBy) {
        this(name, events, procedureName, mode, TIMING_AFTER, allowCascade, enabled, definer, version, createdAt,
                updatedAt, updatedBy);
    }

    public TriggerDefinition(String name, Set<EventType> events, String procedureName, String mode, String timing,
            boolean allowCascade, boolean enabled, String definer, long version, long createdAt, long updatedAt,
            String updatedBy) {
        this.name = name;
        this.events = events == null ? new LinkedHashSet<>() : events;
        this.procedureName = procedureName;
        this.mode = mode == null ? MODE_DOCUMENT : mode;
        this.timing = timing == null ? TIMING_AFTER : timing;
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
        result.readCommonFields(object);
        result.events = new LinkedHashSet<>();
        if (object.has(EVENTS_FIELD) && object.get(EVENTS_FIELD).isJsonArray()) {
            for (final var element : object.get(EVENTS_FIELD).asJsonArray().asList()) {
                result.events.add(EventType.valueOf(element.asJsonString().getValue()));
            }
        }
        result.procedureName = stringOrNull(object, PROCEDURE_NAME_FIELD);
        final var mode = stringOrNull(object, MODE_FIELD);
        result.mode = mode == null ? MODE_DOCUMENT : mode;
        final var timing = stringOrNull(object, TIMING_FIELD);
        result.timing = timing == null ? TIMING_AFTER : timing;
        result.allowCascade = booleanOrDefault(object, ALLOW_CASCADE_FIELD, false);
        result.definer = stringOrNull(object, DEFINER_FIELD);
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
        json.add(TIMING_FIELD, new JsonString(timing));
        json.add(ALLOW_CASCADE_FIELD, new JsonBoolean(allowCascade));
        json.add(ENABLED_FIELD, new JsonBoolean(enabled));
        if (definer != null) {
            json.add(DEFINER_FIELD, new JsonString(definer));
        }
        json.add(VERSION_FIELD, new JsonNumber(version));
        writeAuditFields(json);
        return json;
    }

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

    public String getTiming() {
        return timing;
    }

    public boolean isBefore() {
        return TIMING_BEFORE.equals(timing);
    }

    public boolean isAllowCascade() {
        return allowCascade;
    }

    public String getDefiner() {
        return definer;
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
                && Objects.equals(mode, that.mode) && Objects.equals(timing, that.timing)
                && Objects.equals(definer, that.definer) && Objects.equals(updatedBy, that.updatedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, events, procedureName, mode, timing, allowCascade, enabled, definer, version,
                createdAt, updatedAt, updatedBy);
    }

    @Override
    public String toString() {
        return "TriggerDefinition(name=" + name + ", events=" + events + ", procedureName=" + procedureName + ", mode="
                + mode + ", timing=" + timing + ", allowCascade=" + allowCascade + ", enabled=" + enabled + ", definer="
                + definer + ", version=" + version + ")";
    }
}
