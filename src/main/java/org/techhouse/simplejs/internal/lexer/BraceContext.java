package org.techhouse.simplejs.internal.lexer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsSeparator;

public final class BraceContext {
    public static final Set<String> BLOCK_KEYWORDS = Set.of("else", "do", "try", "finally");

    public static final Set<String> HEADER_KEYWORDS = Set.of("if", "for", "while", "switch", "catch");

    public final Deque<Boolean> open = new ArrayDeque<>();
    public int bodyNesting = -1;
    public boolean bodyIsStatement;
    public boolean closedBlock;
    public boolean closedHeader;

    public void observe(JsBaseElement token, JsBaseElement previous) {
        if (token.getType() == JsType.KEYWORD) {
            observeKeyword(((JsKeyword) token).getValue(), previous);
            return;
        }
        if (token.getType() != JsType.SEPARATOR) {
            return;
        }
        switch (((JsSeparator) token).getValue()) {
            case '(' -> open.push(isHeader(previous));
            case '[' -> open.push(Boolean.FALSE);
            case ')' -> closedHeader = pop();
            case ']' -> pop();
            case '{' -> {
                open.push(bodyNesting == open.size() ? bodyIsStatement : startsStatement(previous));
                bodyNesting = -1;
            }
            case '}' -> closedBlock = pop();
            default -> {
            }
        }
    }

    public void observeKeyword(String keyword, JsBaseElement previous) {
        if ("function".equals(keyword) || "class".equals(keyword)) {
            bodyNesting = open.size();
            bodyIsStatement = startsStatement(previous);
        }
    }

    public boolean isHeader(JsBaseElement previous) {
        return previous != null && previous.getType() == JsType.KEYWORD
                && HEADER_KEYWORDS.contains(((JsKeyword) previous).getValue());
    }

    public boolean pop() {
        return !open.isEmpty() && Boolean.TRUE.equals(open.pop());
    }

    public boolean closedBlock() {
        return closedBlock;
    }

    public boolean startsStatement(JsBaseElement previous) {
        if (previous == null) {
            return true;
        }
        if (previous.getType() == JsType.KEYWORD) {
            return BLOCK_KEYWORDS.contains(((JsKeyword) previous).getValue());
        }
        if (previous.getType() != JsType.SEPARATOR) {
            return false;
        }
        final var c = ((JsSeparator) previous).getValue();
        return c == ';' || c == '{' || (c == ')' && closedHeader) || (c == '}' && closedBlock);
    }
}
