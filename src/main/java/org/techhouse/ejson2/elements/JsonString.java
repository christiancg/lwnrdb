package org.techhouse.ejson2.elements;

public class JsonString extends JsonPrimitive<String> {
    public JsonString() {
        this.value = "";
    }

    public JsonString(String value) {
        this.value = value;
    }
}
