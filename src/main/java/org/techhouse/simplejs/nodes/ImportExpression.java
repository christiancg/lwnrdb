package org.techhouse.simplejs.nodes;

public class ImportExpression extends Expression {
    private final Expression source;
    private final Expression options;

    public ImportExpression(Expression source, Expression options) {
        this.source = source;
        this.options = options;
    }

    public Expression getSource() {
        return source;
    }

    public Expression getOptions() {
        return options;
    }
}
