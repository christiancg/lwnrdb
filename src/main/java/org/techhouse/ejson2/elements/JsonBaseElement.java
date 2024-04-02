package org.techhouse.ejson2.elements;

public abstract class JsonBaseElement {
    public enum JsonType {
        ARRAY,
        OBJECT,
        BOOLEAN,
        NULL,
        STRING,
        DOUBLE,
        SYNTAX
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
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
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

    public JsonNull asJsonNull() {
        return (JsonNull) this;
    }
}
