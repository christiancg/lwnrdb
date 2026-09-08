package org.techhouse.ops.req.agg.operators;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;

public class ScriptOperator extends BaseOperator {
    private String source;

    public ScriptOperator(String source) {
        super(OperatorType.SCRIPT);
        this.source = source;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
