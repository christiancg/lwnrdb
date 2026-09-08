package org.techhouse.simplejs.nodes;

import java.util.List;

public class Program extends JsNode {
    private final List<Statement> body;

    public Program(List<Statement> body) {
        this.body = body;
    }

    public List<Statement> getBody() {
        return body;
    }
}
