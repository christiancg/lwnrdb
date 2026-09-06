package org.techhouse.simplejs.nodes;

public class ImportNamespaceSpecifier extends JsNode {
    private final Identifier local;

    public ImportNamespaceSpecifier(Identifier local) {
        this.local = local;
    }

    public Identifier getLocal() {
        return local;
    }
}
