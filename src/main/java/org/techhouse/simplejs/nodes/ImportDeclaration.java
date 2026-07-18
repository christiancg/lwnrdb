package org.techhouse.simplejs.nodes;

import java.util.List;

public class ImportDeclaration extends Statement {
    private final List<JsNode> specifiers;
    private final StringLiteral source;

    public ImportDeclaration(List<JsNode> specifiers, StringLiteral source) {
        this.specifiers = specifiers;
        this.source = source;
    }

    public List<JsNode> getSpecifiers() {
        return specifiers;
    }

    public StringLiteral getSource() {
        return source;
    }
}
