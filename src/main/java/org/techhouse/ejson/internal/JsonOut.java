package org.techhouse.ejson.internal;

public sealed interface JsonOut permits Utf8JsonOut, CharJsonOut, Utf8CountingJsonOut {
    JsonOut append(char c);

    JsonOut append(String text, int start, int end);

    void appendLong(long value);

    int mark();

    void reset(int mark);

    default JsonOut append(String text) {
        return append(text, 0, text.length());
    }

    default void appendEscaped(String text) {
        JsonStrings.appendEscapedRuns(this, text);
    }
}
