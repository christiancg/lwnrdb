package org.techhouse.ejson.elements;

public abstract class JsonBaseElement {
    public enum JsonType {
        ARRAY,
        OBJECT,
        BOOLEAN,
        NULL,
        STRING,
        DOUBLE,
        SYNTAX,
        CUSTOM
    }

    public JsonType getJsonType() {
        return internalGetJsonType(this);
    }

    private static JsonType internalGetJsonType(Object object) {
        return switch (object) {
            case JsonArray ignored -> JsonType.ARRAY;
            case JsonObject ignored -> JsonType.OBJECT;
            case JsonNull ignored -> JsonType.NULL;
            case JsonString ignored -> JsonType.STRING;
            case JsonDouble ignored -> JsonType.DOUBLE;
            case JsonBoolean ignored -> JsonType.BOOLEAN;
            case JsonSyntaxToken ignored -> JsonType.SYNTAX;
            default -> {
                if (JsonCustom.class.isAssignableFrom(object.getClass())) {
                    yield JsonType.CUSTOM;
                }
                throw new IllegalStateException("Unexpected value: " + object);
            }
        };
    }

    public Boolean isJsonObject() {
        return this instanceof JsonObject;
    }

    public Boolean isJsonArray() {
        return this instanceof JsonArray;
    }

    public Boolean isJsonPrimitive() {
        return this instanceof JsonPrimitive;
    }

    public Boolean isJsonNull() {
        return this instanceof JsonNull;
    }

    public Boolean isJsonString() {
        return this instanceof JsonString;
    }

    public Boolean isJsonDouble() {
        return this instanceof JsonDouble;
    }

    public Boolean isJsonBoolean() {
        return this instanceof JsonBoolean;
    }

    public Boolean isJsonCustom() {
        return JsonCustom.class.isAssignableFrom(this.getClass());
    }

    public JsonPrimitive<?> asJsonPrimitive() {
        return (JsonPrimitive<?>) this;
    }

    public JsonObject asJsonObject() {
        return (JsonObject) this;
    }

    public JsonArray asJsonArray() {
        return (JsonArray) this;
    }

    public JsonDouble asJsonDouble() {
        return (JsonDouble) this;
    }

    public JsonString asJsonString() {
        return (JsonString) this;
    }

    public JsonBoolean asJsonBoolean() {
        return (JsonBoolean) this;
    }

    public JsonCustom<?> asJsonCustom() {
        return (JsonCustom<?>) this;
    }

    public abstract JsonBaseElement deepCopy();
}
