package org.techhouse.simplejs.elements;

public abstract class JsBaseElement {
    public enum JsType {
        KEYWORD, IDENTIFIER, NUMBER, STRING, OPERATOR, SEPARATOR, EOF
    }
    private static JsBaseElement.JsType internalGetJsonType(Object object) {
        return switch (object) {
            case JsKeyword ignored -> JsBaseElement.JsType.KEYWORD;
            case JsIdentifier ignored -> JsBaseElement.JsType.IDENTIFIER;
            case JsNumber ignored -> JsBaseElement.JsType.NUMBER;
            case JsString ignored -> JsBaseElement.JsType.STRING;
            case JsOperator ignored -> JsBaseElement.JsType.OPERATOR;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }

}
