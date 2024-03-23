package org.techhouse.ejson2;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.MalformedJsonException;
import java.util.List;

public class JsonReader {

    private record ParseTokenResult(JsonBaseElement element, int tokensToSkip) {
    }

    public <T> T fromJson(String input, Class<T> tClass) throws Exception {
        final var tokens = Lexer.lex(input);
        final var parsed = internalParse(tokens, true);
        final var newInstance = Assigner.assign(parsed.element(), tClass);
        return tClass.cast(newInstance);
    }

    private ParseTokenResult internalParse(List<JsonBaseElement> tokens, boolean isRoot) {
        final var firstToken = tokens.getFirst();
        if (isRoot && (!firstToken.equals(JsonSyntaxToken.LEFT_BRACE) && !firstToken.equals(JsonSyntaxToken.LEFT_BRACKET))) {
            throw new MalformedJsonException("Json must start with either a left bracket or a left brace");
        }
        if (firstToken.equals(JsonSyntaxToken.LEFT_BRACKET)) {
            return parseArray(skipOneToken(tokens));
        } else if (firstToken.equals(JsonSyntaxToken.LEFT_BRACE)) {
            return parseObject(skipOneToken(tokens));
        } else {
            return new ParseTokenResult(firstToken, 1);
        }
    }

    private ParseTokenResult parseObject(List<JsonBaseElement> tokens) {
        int tokensToSkip = 1; // starts at 1 because already 1 token has been skipped on internalParse method
        final var obj = new JsonObject();
        var firstToken = tokens.getFirst();
        if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
            tokensToSkip++;
            return new ParseTokenResult(obj, tokensToSkip);
        }
        for (int i = 0; i < 100; i++) {
            firstToken = tokens.getFirst();
            var propertyName = "";
            if (firstToken.getJsonType() == JsonBaseElement.JsonType.STRING) {
                propertyName = ((JsonString) firstToken).getValue();
                tokens = skipOneToken(tokens);
                tokensToSkip++;
            } else {
                throw new MalformedJsonException("Expected string key, got " + firstToken.getJsonType());
            }
            firstToken = tokens.getFirst();
            if (!firstToken.equals(JsonSyntaxToken.COLON)) {
                throw new MalformedJsonException("Expected colon after key in object, got: " + firstToken.getJsonType());
            }
            tokens = skipOneToken(tokens);
            tokensToSkip++;
            final var parsedValue = internalParse(tokens, false);
            tokensToSkip += parsedValue.tokensToSkip();
            obj.add(propertyName, parsedValue.element());
            tokens = skipTokens(tokens, parsedValue);
            firstToken = tokens.getFirst();
            if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACE)) {
                tokensToSkip++;
                return new ParseTokenResult(obj, tokensToSkip);
            } else if (!firstToken.equals(JsonSyntaxToken.COMMA)) {
                throw new MalformedJsonException("Expected comma after pair in object, got: " + firstToken.getJsonType());
            }
            tokens = skipOneToken(tokens);
            tokensToSkip++;
        }
        throw new MalformedJsonException("Expected end-of-object bracket");
    }

    private ParseTokenResult parseArray(List<JsonBaseElement> tokens) {
        int tokensToSkip = 1;  // starts at 1 because already 1 token has been skipped on internalParse method
        final var arr = new JsonArray();
        var firstToken = tokens.getFirst();
        if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACKET)) {
            tokensToSkip++;
            return new ParseTokenResult(arr, tokensToSkip);
        }
        while (true) {
            final var parsed = internalParse(tokens, false);
            arr.add(parsed.element());
            tokens = skipTokens(tokens, parsed);
            firstToken = tokens.getFirst();
            if (firstToken.equals(JsonSyntaxToken.RIGHT_BRACKET)) {
                tokensToSkip++;
                return new ParseTokenResult(arr, parsed.tokensToSkip() + tokensToSkip);
            } else if (firstToken.equals(JsonSyntaxToken.COMMA)) {
                throw new MalformedJsonException("Expected comma after object in array");
            }
        }
    }

    private List<JsonBaseElement> skipOneToken(List<JsonBaseElement> tokens) {
        return tokens.subList(1, tokens.size());
    }

    private List<JsonBaseElement> skipTokens(List<JsonBaseElement> tokens, ParseTokenResult element) {
        return tokens.subList(element.tokensToSkip(), tokens.size());
    }
}
