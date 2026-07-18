package org.techhouse.simplejs.nodes;

public class ExportSpecifier extends JsNode {
    private final Expression local;
    private final Expression exported;

    public ExportSpecifier(Expression local, Expression exported) {
        this.local = local;
        this.exported = exported;
    }

    public Expression getLocal() {
        return local;
    }

    public Expression getExported() {
        return exported;
    }
}
