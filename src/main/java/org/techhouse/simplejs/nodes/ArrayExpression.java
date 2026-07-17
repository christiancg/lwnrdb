package org.techhouse.simplejs.nodes;

import java.util.List;

public class ArrayExpression extends Expression {
    private final List<Expression> elements;

    public ArrayExpression(List<Expression> elements) {
        this.elements = elements;
    }

    public List<Expression> getElements() {
        return elements;
    }
}
