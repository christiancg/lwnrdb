package org.techhouse.simplejs.nodes;

import java.util.List;

public class FunctionExpression extends Expression {
    private final Identifier name;
    private final List<Identifier> params;
    private final BlockStatement body;
    private final boolean async;
    private final boolean generator;

    public FunctionExpression(Identifier name, List<Identifier> params, BlockStatement body, boolean async,
            boolean generator) {
        this.name = name;
        this.params = params;
        this.body = body;
        this.async = async;
        this.generator = generator;
    }

    public Identifier getName() {
        return name;
    }

    public List<Identifier> getParams() {
        return params;
    }

    public BlockStatement getBody() {
        return body;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isGenerator() {
        return generator;
    }
}
