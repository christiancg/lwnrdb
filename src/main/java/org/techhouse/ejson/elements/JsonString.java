package org.techhouse.ejson.elements;

import org.techhouse.ejson.internal.JsonStrings;

public class JsonString extends JsonPrimitive<String> {
    private static final byte UNCLASSIFIED = 0;
    private static final byte PLAIN_ASCII = 1;
    private static final byte NEEDS_ENCODING = 2;

    protected String value;
    private byte asciiClass;

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

    public boolean isPlainAscii() {
        if (asciiClass == UNCLASSIFIED) {
            asciiClass = JsonStrings.isPlainAscii(get()) ? PLAIN_ASCII : NEEDS_ENCODING;
        }
        return asciiClass == PLAIN_ASCII;
    }
}
