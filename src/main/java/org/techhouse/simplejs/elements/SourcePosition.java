package org.techhouse.simplejs.elements;

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
