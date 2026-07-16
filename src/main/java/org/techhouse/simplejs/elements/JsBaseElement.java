package org.techhouse.simplejs.elements;

public abstract class JsBaseElement {
    public enum JsType {
        KEYWORD, IDENTIFIER, NUMBER, STRING, BOOLEAN, NULL, UNDEFINED, OPERATOR, SEPARATOR, REGEX, TEMPLATE_STRING, EOF
    }

    public JsType getType() {
        return internalGetType(this);
    }

    private static JsType internalGetType(Object object) {
        return switch (object) {
            case JsKeyword ignored -> JsType.KEYWORD;
            case JsIdentifier ignored -> JsType.IDENTIFIER;
            case JsNumber ignored -> JsType.NUMBER;
            case JsString ignored -> JsType.STRING;
            case JsBoolean ignored -> JsType.BOOLEAN;
            case JsNull ignored -> JsType.NULL;
            case JsUndefined ignored -> JsType.UNDEFINED;
            case JsOperator ignored -> JsType.OPERATOR;
            case JsSeparator ignored -> JsType.SEPARATOR;
            case JsRegex ignored -> JsType.REGEX;
            case JsTemplateString ignored -> JsType.TEMPLATE_STRING;
            case JsEOF ignored -> JsType.EOF;
            default -> throw new IllegalStateException("Unexpected value: " + object);
        };
    }
}
