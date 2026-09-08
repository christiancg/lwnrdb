package org.techhouse.simplejs.nodes;

import java.util.List;

public class SwitchStatement extends Statement {
    private final Expression discriminant;
    private final List<SwitchCase> cases;

    public SwitchStatement(Expression discriminant, List<SwitchCase> cases) {
        this.discriminant = discriminant;
        this.cases = cases;
    }

    public Expression getDiscriminant() {
        return discriminant;
    }

    public List<SwitchCase> getCases() {
        return cases;
    }
}
