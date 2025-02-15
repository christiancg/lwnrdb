package org.techhouse.ejson.internal;

import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.*;
import org.techhouse.ejson.exceptions.MissingEndOfStringException;
import org.techhouse.ejson.exceptions.UnexpectedCharacterException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Lexer {
    private static final Set<Character> JSON_SYNTAX = Set.of(',', ':', '[', ']', '{', '}');
    private static final Set<Character> JSON_WHITESPACE = Set.of(' ', '\t', '\b', '\n', '\r');
    private static final int FALSE_LEN = "false".length();
    private static final int TRUE_LEN = "true".length();
    private static final int NULL_LEN = "null".length();
    private static final Set<Character> NUMBER_CHARACTERS = Set.of('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '.');

    public static List<JsonBaseElement> lex(String input) {
        final var tokens = new ArrayList<JsonBaseElement>();
        for (var i = 0; i < input.length(); i++) {
            final var ss = input.substring(i);
            final var ls = lexString(ss);
            if (ls != null) {
                if (JsonCustom.isJsonCustom(ls)) {
                    tokens.add(CustomTypeFactory.getCustomTypeInstance(ls));
                } else {
                    tokens.add(ls);
                }
                i += ls.getValue().length() + 1;
                continue;
            }
            final var ld = lexNumber(ss);
            if (ld != null) {
                tokens.add(ld);
                i += ld.getStrLength() - 1;
                continue;
            }
            final var lb = lexBoolean(ss);
            if (lb != null) {
                tokens.add(lb);
                i += (lb.getValue() ? TRUE_LEN : FALSE_LEN) - 1;
                continue;
            }
            final var ln = lexNull(ss);
            if (ln != null) {
                tokens.add(ln);
                i += NULL_LEN - 1;
                continue;
            }
            final var c = input.charAt(i);
            if (JSON_WHITESPACE.contains(c)) {
                continue;
            }
            if (JSON_SYNTAX.contains(c)) {
                tokens.add(JsonSyntaxToken.fromChar(c));
            } else {
                throw new UnexpectedCharacterException(c, i);
            }
        }
        return tokens;
    }

    private static JsonString lexString(String input) {
        final var builder = new StringBuilder();
        if (input.charAt(0) == '"') {
            input = input.substring(1);
        } else {
            return null;
        }
        for (var c : input.toCharArray()) {
            if (c == '"') {
                return new JsonString(builder.toString());
            } else {
                builder.append(c);
            }
        }
        throw new MissingEndOfStringException();
    }

    private static JsonNull lexNull(String input) {
        final var length = input.length();
        if (length >= NULL_LEN && input.substring(0, NULL_LEN).equals("null")) {
            return JsonNull.INSTANCE;
        }
        return null;
    }

    private static JsonNumber lexNumber(String input) {
        final var strNumber = new StringBuilder();
        for (var c : input.toCharArray()) {
            if (NUMBER_CHARACTERS.contains(c)) {
                strNumber.append(c);
            } else {
                break;
            }
        }
        if (strNumber.isEmpty()) {
            return null;
        } else {
            return new JsonNumber(strNumber.toString());
        }
    }

    private static JsonBoolean lexBoolean(String input) {
        final var length = input.length();
        if (length >= TRUE_LEN && input.substring(0, TRUE_LEN).equals("true")) {
            return new JsonBoolean(true);
        } else if (length >= FALSE_LEN && input.substring(0, FALSE_LEN).equals("false")) {
            return new JsonBoolean(false);
        }
        return null;
    }
}
