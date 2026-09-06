package org.techhouse.simplejs.elements;

// Location of a token in the original source: 0-based character offset and length, plus the
// 1-based line and column of the token's start. Held parallel to the token stream (not on the
// tokens themselves) so the shared JsNull/JsUndefined/JsEOF singletons keep their identity.
public class SourcePosition {
    private final int offset;
    private final int length;
    private final int line;
    private final int column;

    public SourcePosition(int offset, int length, int line, int column) {
        this.offset = offset;
        this.length = length;
        this.line = line;
        this.column = column;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }
}
