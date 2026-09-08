package org.techhouse.simplejs.nodes;

public class ImportSpecifier extends JsNode {
    private final Expression imported;
    private final Identifier local;

    public ImportSpecifier(Expression imported, Identifier local) {
        this.imported = imported;
        this.local = local;
    }

    public Expression getImported() {
        return imported;
    }

    public Identifier getLocal() {
        return local;
    }
}
