package org.techhouse.ejson.elements;

import lombok.Getter;

@Getter
public class JsonDouble extends JsonPrimitive<Double> {
    private int strLength;
    public JsonDouble() {
        this.value = 0.0;
    }

    public JsonDouble(Double value, int strLength) {
        this.value = value;
        this.strLength = strLength;
    }

    public JsonDouble(Integer value) {
        if (value != null) {
            this.value = Double.valueOf(value);
            this.strLength = (int) (Math.log10(value) + 1);
        }
    }

    public JsonDouble(Double value) {
        if (value != null) {
            this.value = value;
            this.strLength = String.valueOf(value).length();
        }
    }

    public JsonDouble(Long value) {
        if (value != null) {
            this.value = Double.valueOf(value);
            this.strLength = (int) (Math.log10(value) + 1);
        }
    }

    public Integer asInteger() {
        return value != null ? value.intValue() : null;
    }
}
