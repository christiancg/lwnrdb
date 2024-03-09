package org.techhouse.ejson2.elements;

import org.techhouse.ejson2.exceptions.UnexpectedCharacterException;

public class JsonSyntaxToken extends JsonBaseElement {
    private enum SyntaxToken {
        LEFT_BRACE,
        RIGHT_BRACE,
        LEFT_BRACKET,
        RIGHT_BRACKET,
        COMMA,
        COLON
    }
    private final SyntaxToken value;
    private JsonSyntaxToken(SyntaxToken value) {
        this.value = value;
    }
    public static final JsonSyntaxToken LEFT_BRACE = new JsonSyntaxToken(SyntaxToken.LEFT_BRACE);
    public static final JsonSyntaxToken RIGHT_BRACE = new JsonSyntaxToken(SyntaxToken.RIGHT_BRACE);
    public static final JsonSyntaxToken LEFT_BRACKET = new JsonSyntaxToken(SyntaxToken.LEFT_BRACKET);
    public static final JsonSyntaxToken RIGHT_BRACKET = new JsonSyntaxToken(SyntaxToken.RIGHT_BRACKET);
    public static final JsonSyntaxToken COMMA = new JsonSyntaxToken(SyntaxToken.COMMA);
    public static final JsonSyntaxToken COLON = new JsonSyntaxToken(SyntaxToken.COLON);

    public static JsonSyntaxToken fromChar(Character c) {
        return switch (c) {
            case '{' -> LEFT_BRACE;
            case '}' -> RIGHT_BRACE;
            case '[' -> LEFT_BRACKET;
            case ']' -> RIGHT_BRACKET;
            case ',' -> COMMA;
            case ':' -> COLON;
            default -> throw new UnexpectedCharacterException(c, 0);
        };
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonSyntaxToken && ((JsonSyntaxToken) other).value == this.value;
    }
}
