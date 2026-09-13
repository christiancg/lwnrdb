package org.techhouse.simplejs.nodes;

import java.util.List;

public class BlockStatement extends Statement {
    private final List<Statement> body;
    private Object hoistPlan;

    public BlockStatement(List<Statement> body) {
        this.body = body;
    }

    public List<Statement> getBody() {
        return body;
    }

    public Object getHoistPlan() {
        return hoistPlan;
    }

    public void setHoistPlan(Object hoistPlan) {
        this.hoistPlan = hoistPlan;
    }

    @Override
    public NodeType getType() {
        return NodeType.BLOCK_STATEMENT;
    }
}
