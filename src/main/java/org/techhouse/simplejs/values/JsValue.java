package org.techhouse.simplejs.values;

public abstract class JsValue {
    public enum JsValueType {
        NUMBER, STRING, BOOLEAN, BIGINT, UNDEFINED, NULL, OBJECT, ARRAY, FUNCTION
    }

    public JsValueType getType() {
        return internalGetType(this);
    }

    private static JsValueType internalGetType(Object object) {
        return switch (object) {
            case JsNumber ignored -> JsValueType.NUMBER;
            case JsString ignored -> JsValueType.STRING;
            case JsBoolean ignored -> JsValueType.BOOLEAN;
            case JsBigInt ignored -> JsValueType.BIGINT;
            case JsUndefined ignored -> JsValueType.UNDEFINED;
            case JsNull ignored -> JsValueType.NULL;
            case JsObject ignored -> JsValueType.OBJECT;
            case JsArray ignored -> JsValueType.ARRAY;
            case JsFunction ignored -> JsValueType.FUNCTION;
            case JsNativeFunction ignored -> JsValueType.FUNCTION;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }
}
