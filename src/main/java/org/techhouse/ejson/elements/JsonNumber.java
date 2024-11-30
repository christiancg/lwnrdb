package org.techhouse.ejson.elements;

import lombok.Getter;

@Getter
public class JsonNumber extends JsonPrimitive<Number> {
    private int strLength;
    public JsonNumber() {
        this.value = 0;
    }

    public JsonNumber(Number value) {
        if (value != null) {
            this.value = value;
            this.strLength = String.valueOf(value).length();
        }
    }

    public JsonNumber(String value) {
        if (value != null) {
            final var doubleNumber = Double.parseDouble(value);
            if (doubleNumber % 1.0 == 0) {
                this.value = Integer.valueOf(value);
            } else {
                this.value = doubleNumber;
            }
            this.strLength = value.length();
        }
    }

    public Integer asInteger() {
        return value != null ? value.intValue() : null;
    }
}
