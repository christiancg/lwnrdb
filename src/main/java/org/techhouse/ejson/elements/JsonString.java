package org.techhouse.ejson.elements;

public class JsonString extends JsonPrimitive<String> {
    protected String value;

    public JsonString() {
        this.value = "";
    }

    public JsonString(String value) {
        this.value = value;
    }

    @Override
    protected String get() {
        return value;
    }
}
