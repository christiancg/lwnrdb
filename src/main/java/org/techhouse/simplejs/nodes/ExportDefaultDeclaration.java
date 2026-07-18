package org.techhouse.simplejs.nodes;

public class ExportDefaultDeclaration extends Statement {
    private final JsNode declaration;

    public ExportDefaultDeclaration(JsNode declaration) {
        this.declaration = declaration;
    }

    public JsNode getDeclaration() {
        return declaration;
    }
}
