package org.techhouse.simplejs.internal;

import org.techhouse.simplejs.elements.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Lexer {
    private Lexer() {}

    private static final Set<String> JS_KEYWORD = Set.of("if", "do", "while", "for", "in", "of", "switch", "case", "default", "var", "let", "const", "break", "continue", "return", "try", "catch", "finally", "throw", "async", "await", "yield", "function", "import", "export", "this", "constructor", "new", "class");
    private static final Set<Character> JS_OPERATOR = Set.of('=', '<', '>', '*', '/', '%', '!');
    private static final Set<Character> JS_SYNTAX = Set.of(',', ';', '.' , '(', ')', '[', ']', '{', '}');

    private Character peek(String sourceCode, int pos) {
        if (pos >= sourceCode.length()) return null;
        return sourceCode.charAt(pos);
    }

    private Character advance(String sourceCode, int pos) {
        if (pos >= sourceCode.length()) return null;
        return sourceCode.charAt(pos+1);
    }

    public List<JsBaseElement> lex(String sourceCode) {
        int pos = 0;
        List<JsBaseElement> tokens = new ArrayList<>();

        while (pos < sourceCode.length()) {
            Character current = peek(sourceCode, pos);

            // Skip Whitespace
            if (current != null && Character.isWhitespace(current)) {
                advance(sourceCode, pos);
                pos++;
                continue;
            } else if (current == null) {
                break;
            }

            // Handle Numeric Literals
            if (Character.isDigit(current)) {
                StringBuilder sb = new StringBuilder();
                Character nextChar = peek(sourceCode, pos);
                if (nextChar != null) {
                    while (Character.isDigit(nextChar)) {
                        final var next = advance(sourceCode, pos);
                        if (next != null) {
                            sb.append(next);
                        }
                        pos++;
                    }
                    tokens.add(new JsNumber(Double.parseDouble(sb.toString())));
                    continue;
                } else {
                    break;
                }
            }

            // Handle Identifiers & Keywords
            if (Character.isLetter(current) || current == '_' || current == '$') {
                StringBuilder sb = new StringBuilder();
                Character nextChar = peek(sourceCode, pos);
                if (nextChar != null) {
                    while (Character.isLetterOrDigit(nextChar) || nextChar == '_' || nextChar == '$') {
                        final var next = advance(sourceCode, pos);
                        if (next != null) {
                            sb.append(next);
                        }
                        pos++;
                    }
                    String val = sb.toString();
                    if (JS_KEYWORD.contains(val)) {
                        tokens.add(new JsKeyword(val));
                    } else {
                        tokens.add(new JsIdentifier(val));
                    }
                    continue;
                } else {
                    break;
                }
            }

            // Handle Simple Operators / Separators
            if (current == '=' || current == '+' || current == '-' || current == '*') {
                final var next = advance(sourceCode, pos);
                if (next != null) {
                    tokens.add(new JsOperator(String.valueOf(next)));
                }
                pos++;
                continue;
            }
            if (current == ';' || current == '(' || current == ')' || current == '{' || current == '}') {
                final var next = advance(sourceCode, pos);
                if (next != null) {
                    pos++;
                    tokens.add(new JsSeparator(next));
                }
                continue;
            }
            throw new RuntimeException("Unknown character: " + current);
        }
        tokens.add(JsEOF.getInstance());
        return tokens;
    }
}
