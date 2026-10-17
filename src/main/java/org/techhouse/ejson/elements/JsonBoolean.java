package org.techhouse.ejson.elements;

public final class JsonBoolean extends JsonPrimitive<Boolean> {
    public static final JsonBoolean TRUE = new JsonBoolean(true);
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    private final Boolean value;

    private JsonBoolean(boolean value) {
        this.value = value;
    }

    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    protected Boolean get() {
        return value;
    }
}
