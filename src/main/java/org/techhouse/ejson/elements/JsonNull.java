package org.techhouse.ejson.elements;

public class JsonNull extends JsonBaseElement {
    public static final JsonNull INSTANCE = new JsonNull();

    @Override
    public int hashCode() {
        return JsonNull.class.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNull;
    }

    @Override
    public JsonBaseElement deepCopy() {
        return JsonNull.INSTANCE;
    }
}
