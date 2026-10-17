package org.techhouse.ejson.internal;

public sealed interface JsonOut permits Utf8JsonOut, CharJsonOut {
    JsonOut append(char c);

    JsonOut append(String text, int start, int end);

    JsonOut appendLong(long value);

    int mark();

    void reset(int mark);

    default JsonOut append(String text) {
        return append(text, 0, text.length());
    }
}
