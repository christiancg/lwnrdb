package org.techhouse.simplejs.nodes;

import java.util.List;

public class ImportDeclaration extends Statement {
    private final List<JsNode> specifiers;
    private final StringLiteral source;
    private final List<ImportAttribute> attributes;

    public ImportDeclaration(List<JsNode> specifiers, StringLiteral source, List<ImportAttribute> attributes) {
        this.specifiers = specifiers;
        this.source = source;
        this.attributes = attributes;
    }

    public List<JsNode> getSpecifiers() {
        return specifiers;
    }

    public StringLiteral getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }
}
