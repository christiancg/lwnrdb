package org.techhouse.simplejs.nodes;

import java.util.List;

public class SwitchCase extends JsNode {
    private final Expression test;
    private final List<Statement> consequent;

    public SwitchCase(Expression test, List<Statement> consequent) {
        this.test = test;
        this.consequent = consequent;
    }

    public Expression getTest() {
        return test;
    }

    public List<Statement> getConsequent() {
        return consequent;
    }
}
