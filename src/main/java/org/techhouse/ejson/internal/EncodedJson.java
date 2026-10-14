package org.techhouse.ejson.internal;

public record EncodedJson(byte[] bytes, int length) {
    public static EncodedJson of(byte[] exact) {
        return new EncodedJson(exact, exact.length);
    }
}
