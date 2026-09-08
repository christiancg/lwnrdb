package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

final class TemporalCursor {
    final String source;
    int pos;

    TemporalCursor(String source) {
        this.source = source;
    }

    boolean atEnd() {
        return pos >= source.length();
    }

    char peek() {
        return source.charAt(pos);
    }

    void advance() {
        pos++;
    }

    void expectDash() {
        if (atEnd() || peek() != '-') {
            throw new RangeErrorException("Invalid Temporal string: " + source);
        }
        advance();
    }
}
