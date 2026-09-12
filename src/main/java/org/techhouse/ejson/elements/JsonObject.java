package org.techhouse.ejson.elements;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class JsonObject extends JsonBaseElement {
    private final Map<String, JsonBaseElement> members = new LinkedHashMap<>();

    public void add(String property, JsonBaseElement value) {
        put(property, value == null ? JsonNull.INSTANCE : value);
    }

    public void add(String property, String value) {
        put(property, new JsonString(value));
    }

    private void put(String property, JsonBaseElement value) {
        members.put(Objects.requireNonNull(property, "property == null"), value);
    }

    public void addProperty(String property, String value) {
        add(property, value);
    }

    public void addProperty(String property, Boolean value) {
        put(property, new JsonBoolean(value));
    }

    public void addProperty(String property, Integer value) {
        put(property, new JsonNumber(value));
    }

    public void addProperty(String property, Number value) {
        put(property, new JsonNumber(value));
    }

    public void addProperty(String property, Long value) {
        put(property, new JsonNumber(value));
    }

    public void remove(String property) {
        members.remove(property);
    }

    public Set<Map.Entry<String, JsonBaseElement>> entrySet() {
        return members.entrySet();
    }

    public int size() {
        return members.size();
    }
    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean has(String memberName) {
        return members.containsKey(memberName);
    }
    public JsonBaseElement get(String memberName) {
        return members.get(memberName);
    }

    @Override
    public boolean equals(Object o) {
        return (o == this) || (o instanceof JsonObject obj && obj.members.equals(members));
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }

    @Override
    public JsonObject deepCopy() {
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonBaseElement> entry : members.entrySet()) {
            result.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return result;
    }
}
