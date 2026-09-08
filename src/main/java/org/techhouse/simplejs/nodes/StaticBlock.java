package org.techhouse.simplejs.nodes;

import java.util.List;

public class StaticBlock extends JsNode {
    private final List<Statement> body;

    public StaticBlock(List<Statement> body) {
        this.body = body;
    }

    public List<Statement> getBody() {
        return body;
    }
}
