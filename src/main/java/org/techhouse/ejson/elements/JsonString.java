package org.techhouse.ejson.elements;

public class JsonString extends JsonPrimitive<String> {
    public JsonString() {
        this.value = "";
    }

    public JsonString(String value) {
        this.value = value;
    }
}
