package org.techhouse.ops.req.agg.operators;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;

// A predicate written in SimpleJS: (doc) => boolean, applied to every candidate document. Opaque to the
// indexes, so it never resolves ids on its own - inside a conjunction the other operands still can, and the
// script then runs only on what they produce.
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
