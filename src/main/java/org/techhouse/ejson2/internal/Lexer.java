package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.MissingEndOfStringException;
import org.techhouse.ejson2.exceptions.UnexpectedCharacterException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class Lexer {
    private static final HashSet<Character> JSON_SYNTAX = new HashSet<>() {{
        add(',');
        add(':');
        add('[');
        add(']');
        add('{');
        add('}');
    }};
    private static final HashSet<Character> JSON_WHITESPACE = new HashSet<>() {{
        add(' ');
        add('\t');
        add('\b');
        add('\n');
        add('\r');
    }};
    private static final int FALSE_LEN = "false".length();
    private static final int TRUE_LEN = "true".length();
    private static final int NULL_LEN = "null".length();
    private static final HashSet<Character> NUMBER_CHARACTERS = new HashSet<>() {{
        add('0');
        add('1');
        add('2');
        add('3');
        add('4');
        add('5');
        add('6');
        add('7');
        add('8');
        add('9');
        add('-');
        add('.');
    }};

    public static List<JsonBaseElement> lex(String input) {
        final var tokens = new ArrayList<JsonBaseElement>();
        for (var i = 0; i < input.length(); i++) {
            final var ss = input.substring(i);
            final var ls = lexString(ss);
            if (ls != null) {
                tokens.add(ls);
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

    private static JsonDouble lexNumber(String input) {
        // TODO: add missing validations, for example, - can only be at start, only one .
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
            return new JsonDouble(Double.valueOf(strNumber.toString()), strNumber.length());
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
