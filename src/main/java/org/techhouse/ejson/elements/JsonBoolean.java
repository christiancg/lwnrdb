package org.techhouse.ejson.elements;

public class JsonBoolean extends JsonPrimitive<Boolean> {
    public JsonBoolean() {
        this.value = false;
    }

    public JsonBoolean(Boolean value) {
        this.value = value;
    }
}
