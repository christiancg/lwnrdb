package org.techhouse.ejson.elements;

import org.techhouse.ejson.exceptions.MalformedJsonException;

public class JsonNumber extends JsonPrimitive<Number> {
    private final double number;

    public JsonNumber() {
        this(0.0);
    }

    public JsonNumber(double value) {
        this.number = value;
    }

    public JsonNumber(Number value) {
        this(value == null ? 0.0 : value.doubleValue());
    }

    public JsonNumber(String value) {
        this(parseFinite(value));
    }

    private static double parseFinite(String value) {
        if (value == null) {
            return 0.0;
        }
        final var parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) {
            throw new MalformedJsonException(
                    "The number " + value + " is outside the range a JSON number can represent");
        }
        return parsed == 0.0 ? 0.0 : parsed;
    }

    public double doubleValue() {
        return number;
    }

    @Override
    protected Number get() {
        return number;
    }

    @Override
    public Number getValue() {
        return number;
    }

    public Integer asInteger() {
        return (int) number;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(number == 0.0 ? 0.0 : number);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final var other = ((JsonNumber) obj).number;
        return number == other || (Double.isNaN(number) && Double.isNaN(other));
    }
}
