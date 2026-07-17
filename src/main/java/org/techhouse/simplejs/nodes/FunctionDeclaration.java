package org.techhouse.simplejs.nodes;

import java.util.List;

public class FunctionDeclaration extends Statement {
    private final Identifier name;
    private final List<Identifier> params;
    private final BlockStatement body;

    public FunctionDeclaration(Identifier name, List<Identifier> params, BlockStatement body) {
        this.name = name;
        this.params = params;
        this.body = body;
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
}
