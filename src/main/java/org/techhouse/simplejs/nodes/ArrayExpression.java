package org.techhouse.simplejs.nodes;

import java.util.List;

public class ArrayExpression extends Expression {
    private final List<Expression> elements;
    private final boolean trailingComma;

    public ArrayExpression(List<Expression> elements) {
        this(elements, false);
    }

    public ArrayExpression(List<Expression> elements, boolean trailingComma) {
        this.elements = elements;
        this.trailingComma = trailingComma;
    }

    public List<Expression> getElements() {
        return elements;
    }

    public boolean hasTrailingComma() {
        return trailingComma;
    }
}
