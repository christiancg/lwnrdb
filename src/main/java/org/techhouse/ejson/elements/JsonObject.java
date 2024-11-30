package org.techhouse.ejson.elements;

import org.techhouse.ejson.internal.LinkedTreeMap;

import java.util.Map;
import java.util.Set;

public class JsonObject extends JsonBaseElement {
    private final LinkedTreeMap<String, JsonBaseElement> members = new LinkedTreeMap<>(false);

    public void add(String property, JsonBaseElement value) {
        members.put(property, value == null ? JsonNull.INSTANCE : value);
    }

    public void add(String property, String value) {
        members.put(property, new JsonString(value));
    }

    public void addProperty(String property, String value) {
        add(property, value);
    }

    public void addProperty(String property, Integer value) {
        members.put(property, new JsonNumber(value));
    }

    public void addProperty(String property, Number value) {
        members.put(property, new JsonNumber(value));
    }

    public void addProperty(String property, Long value) {
        members.put(property, new JsonNumber(value));
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
        return (o == this) || (o instanceof JsonObject
                && ((JsonObject) o).members.equals(members));
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
