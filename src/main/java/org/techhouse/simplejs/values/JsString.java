package org.techhouse.simplejs.values;

public final class JsString extends JsValue {
    public static final int BUFFER_THRESHOLD = 256;

    private String value;
    private StringBuilder buffer; // NOPMD
    private final int length;

    public JsString(String value) {
        this.value = value;
        this.length = value == null ? 0 : value.length();
    }

    private JsString(StringBuilder buffer, int length) {
        this.buffer = buffer;
        this.length = length;
    }

    public int length() {
        return length;
    }

    public char charAt(int index) {
        final var flat = value;
        return flat != null ? flat.charAt(index) : buffer.charAt(index);
    }

    public String getValue() {
        var flat = value;
        final var shared = buffer;
        if (flat == null && shared != null) {
            flat = shared.substring(0, length);
            value = flat;
            if (shared.length() != length) {
                buffer = null;
            }
        }
        return flat;
    }

    public JsString concat(String tail) {
        if (tail.isEmpty()) {
            return this;
        }
        final var shared = buffer;
        final var total = length + tail.length();
        if (shared != null && shared.length() == length) {
            shared.append(tail);
            return new JsString(shared, total);
        }
        if (total < BUFFER_THRESHOLD) {
            return new JsString(getValue().concat(tail));
        }
        final var fresh = new StringBuilder(total);
        if (value != null) {
            fresh.append(value);
        } else {
            fresh.append(shared, 0, length);
        }
        fresh.append(tail);
        return new JsString(fresh, total);
    }

    public boolean appendsInPlace() {
        final var shared = buffer;
        return shared != null && shared.length() == length;
    }

    @Override
    public JsValueType getType() {
        return JsValueType.STRING;
    }
}
