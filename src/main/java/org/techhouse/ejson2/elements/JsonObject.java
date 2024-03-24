package org.techhouse.ejson2.elements;

import org.techhouse.ejson2.internal.LinkedTreeMap;

import java.util.Map;
import java.util.Set;

public class JsonObject extends JsonBaseElement {
    private final LinkedTreeMap<String, JsonBaseElement> members = new LinkedTreeMap<>(false);

    public void add(String property, JsonBaseElement value) {
        members.put(property, value == null ? JsonNull.INSTANCE : value);
    }

    public JsonBaseElement remove(String property) {
        return members.remove(property);
    }

    public Set<Map.Entry<String, JsonBaseElement>> entrySet() {
        return members.entrySet();
    }

    public Set<String> keySet() {
        return members.keySet();
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
}
