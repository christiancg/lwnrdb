package org.techhouse.ejson2.elements;

public class JsonBoolean extends JsonPrimitive<Boolean> {
    public JsonBoolean() {
        this.value = false;
    }

    public JsonBoolean(Boolean value) {
        this.value = value;
    }
}
