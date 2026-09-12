package org.techhouse.ejson.elements;

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
            if (doubleNumber % 1.0 == 0 && Math.abs(doubleNumber) <= Integer.MAX_VALUE) {
                // Integer.valueOf would throw on "2.0"; values past the int range stay doubles because
                // an (int) cast clamps them silently.
                this.value = (int) doubleNumber;
            } else {
                this.value = doubleNumber;
            }
            this.strLength = value.length();
        }
    }

    public int getStrLength() {
        return strLength;
    }

    public Integer asInteger() {
        return value != null ? value.intValue() : null;
    }
}
