package org.techhouse.simplejs.nodes;

import java.util.List;

public class BlockStatement extends Statement {
    private final List<Statement> body;

    public BlockStatement(List<Statement> body) {
        this.body = body;
    }

    public List<Statement> getBody() {
        return body;
    }
}
