package org.techhouse.simplejs.internal.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;

public abstract class TokenStream {
    protected final List<JsBaseElement> tokens;
    protected final List<SourcePosition> positions;
    protected final List<Boolean> newlineBefore;
    protected final Deque<Boolean> noInStack = new ArrayDeque<>();
    protected int pos;

    protected TokenStream(List<JsBaseElement> tokens, List<SourcePosition> positions, List<Boolean> newlineBefore) {
        this.tokens = tokens;
        this.positions = positions;
        this.newlineBefore = newlineBefore;
    }

    protected boolean newlineBeforeCurrent() {
        return newlineBefore != null && Boolean.TRUE.equals(newlineBefore.get(pos));
    }

    protected boolean newlineBeforePeek() {
        return newlineBefore != null
                && Boolean.TRUE.equals(newlineBefore.get(Math.min(pos + 1, newlineBefore.size() - 1)));
    }

    protected JsBaseElement current() {
        return tokens.get(pos);
    }

    protected JsBaseElement peek() {
        return peekAt(1);
    }

    protected JsBaseElement peekAt(int offset) {
        return tokens.get(Math.min(pos + offset, tokens.size() - 1));
    }

    protected JsBaseElement advance() {
        final var t = current();
        if (t.getType() != JsType.EOF) {
            pos++;
        }
        return t;
    }

    protected boolean atEnd() {
        return current().getType() == JsType.EOF;
    }

    protected void consumeSemicolon() {
        if (matchSeparator(';')) {
            return;
        }
        if (newlineBefore == null || isSeparator('}') || atEnd() || newlineBeforeCurrent()) {
            return;
        }
        throw error();
    }

    protected void consumeDoWhileSemicolon() {
        matchSeparator(';');
    }

    protected <T> T withNoIn(Supplier<T> parse) {
        return withInContext(Boolean.TRUE, parse);
    }

    protected <T> T withInAllowed(Supplier<T> parse) {
        return withInContext(Boolean.FALSE, parse);
    }

    protected <T> T withInContext(Boolean suppressIn, Supplier<T> parse) {
        noInStack.push(suppressIn);
        try {
            return parse.get();
        } finally {
            noInStack.pop();
        }
    }

    protected boolean isSeparator(char c) {
        final var t = current();
        return t.getType() == JsType.SEPARATOR && ((JsSeparator) t).getValue() == c;
    }

    protected boolean matchSeparator(char c) {
        if (isSeparator(c)) {
            advance();
            return true;
        }
        return false;
    }

    protected void expectSeparator(char c) {
        if (!matchSeparator(c)) {
            throw error();
        }
    }

    protected boolean isOperator(String op) {
        final var t = current();
        return t.getType() == JsType.OPERATOR && ((JsOperator) t).getValue().equals(op);
    }

    protected boolean matchOperator(String op) {
        if (isOperator(op)) {
            advance();
            return true;
        }
        return false;
    }

    protected void expectOperator(String op) {
        if (!matchOperator(op)) {
            throw error();
        }
    }

    protected boolean isKeyword(String kw) {
        final var t = current();
        return t.getType() == JsType.KEYWORD && ((JsKeyword) t).getValue().equals(kw);
    }

    protected boolean matchKeyword(String kw) {
        if (isKeyword(kw)) {
            advance();
            return true;
        }
        return false;
    }

    protected void expectKeyword(String kw) {
        if (!matchKeyword(kw)) {
            throw error();
        }
    }

    protected boolean isContextualKeyword(String word) {
        final var t = current();
        return t.getType() == JsType.IDENTIFIER && ((JsIdentifier) t).getValue().equals(word)
                && !((JsIdentifier) t).isEscaped();
    }

    protected boolean matchContextualKeyword(String word) {
        if (isContextualKeyword(word)) {
            advance();
            return true;
        }
        return false;
    }

    protected void expectContextualKeyword(String word) {
        if (!matchContextualKeyword(word)) {
            throw error();
        }
    }

    protected RuntimeException error() {
        final var position = positions != null ? positions.get(pos) : null;
        if (atEnd()) {
            return position != null
                    ? new UnexpectedEndOfInputException(position.getLine(), position.getColumn())
                    : new UnexpectedEndOfInputException();
        }
        return position != null
                ? new UnexpectedTokenException(describe(current()), position.getLine(), position.getColumn())
                : new UnexpectedTokenException(describe(current()), pos);
    }

    protected String describe(JsBaseElement t) {
        return switch (t.getType()) {
            case KEYWORD -> ((JsKeyword) t).getValue();
            case IDENTIFIER -> ((JsIdentifier) t).getValue();
            case PRIVATE_IDENTIFIER -> "#" + ((JsPrivateIdentifier) t).getValue();
            case NUMBER -> String.valueOf(((JsNumber) t).getValue());
            case BIGINT -> ((JsBigInt) t).getValue() + "n";
            case STRING -> '"' + ((JsString) t).getValue() + '"';
            case BOOLEAN -> String.valueOf(((JsBoolean) t).getValue());
            case NULL -> "null";
            case UNDEFINED -> "undefined";
            case OPERATOR -> ((JsOperator) t).getValue();
            case SEPARATOR -> String.valueOf(((JsSeparator) t).getValue());
            case REGEX -> "/" + ((JsRegex) t).getPattern() + "/" + ((JsRegex) t).getFlags();
            case TEMPLATE_STRING -> "template literal";
            case EOF -> "<eof>";
        };
    }
}
