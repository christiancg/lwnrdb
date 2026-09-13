package org.techhouse.simplejs.nodes;

public class ImportDefaultSpecifier extends JsNode {
    private final Identifier local;

    public ImportDefaultSpecifier(Identifier local) {
        this.local = local;
    }

    public Identifier getLocal() {
        return local;
    }

    @Override
    public NodeType getType() {
        return NodeType.IMPORT_DEFAULT_SPECIFIER;
    }
}
