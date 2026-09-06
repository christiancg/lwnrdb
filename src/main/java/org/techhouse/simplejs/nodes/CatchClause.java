package org.techhouse.simplejs.nodes;

public class CatchClause extends JsNode {
    private final JsNode param;
    private final BlockStatement body;

    public CatchClause(JsNode param, BlockStatement body) {
        this.param = param;
        this.body = body;
    }

    public JsNode getParam() {
        return param;
    }

    public BlockStatement getBody() {
        return body;
    }
}
