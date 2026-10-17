package org.techhouse.ejson.internal;

public final class CharJsonOut implements JsonOut {
    @SuppressWarnings("PMD.AvoidStringBufferField")
    private final StringBuilder builder;

    public CharJsonOut() {
        this(new StringBuilder());
    }

    public CharJsonOut(StringBuilder builder) {
        this.builder = builder;
    }

    @Override
    public JsonOut append(char c) {
        builder.append(c);
        return this;
    }

    @Override
    public JsonOut append(String text, int start, int end) {
        builder.append(text, start, end);
        return this;
    }

    @Override
    public JsonOut appendLong(long value) {
        builder.append(value);
        return this;
    }

    @Override
    public int mark() {
        return builder.length();
    }

    @Override
    public void reset(int mark) {
        builder.setLength(mark);
    }

    @Override
    public String toString() {
        return builder.toString();
    }
}
