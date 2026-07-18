package org.techhouse.simplejs.nodes;

public class CatchClause extends JsNode {
    private final Identifier param;
    private final BlockStatement body;

    public CatchClause(Identifier param, BlockStatement body) {
        this.param = param;
        this.body = body;
    }

    public Identifier getParam() {
        return param;
    }

    public BlockStatement getBody() {
        return body;
    }
}
