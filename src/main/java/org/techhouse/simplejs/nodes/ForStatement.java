package org.techhouse.simplejs.nodes;

public class ForStatement extends Statement {
    private final JsNode init;
    private final Expression test;
    private final Expression update;
    private final Statement body;
    private final boolean bindingsCapturable;

    public ForStatement(JsNode init, Expression test, Expression update, Statement body) {
        this(init, test, update, body, true);
    }

    public ForStatement(JsNode init, Expression test, Expression update, Statement body, boolean bindingsCapturable) {
        this.init = init;
        this.test = test;
        this.update = update;
        this.body = body;
        this.bindingsCapturable = bindingsCapturable;
    }

    public boolean bindingsCapturable() {
        return bindingsCapturable;
    }

    public JsNode getInit() {
        return init;
    }

    public Expression getTest() {
        return test;
    }

    public Expression getUpdate() {
        return update;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public NodeType getType() {
        return NodeType.FOR_STATEMENT;
    }
}
