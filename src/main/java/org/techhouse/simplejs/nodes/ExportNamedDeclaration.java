package org.techhouse.simplejs.nodes;

import java.util.List;

public class ExportNamedDeclaration extends Statement {
    private final JsNode declaration;
    private final List<ExportSpecifier> specifiers;
    private final StringLiteral source;
    private final List<ImportAttribute> attributes;

    public ExportNamedDeclaration(JsNode declaration, List<ExportSpecifier> specifiers, StringLiteral source,
            List<ImportAttribute> attributes) {
        this.declaration = declaration;
        this.specifiers = specifiers;
        this.source = source;
        this.attributes = attributes;
    }

    public JsNode getDeclaration() {
        return declaration;
    }

    public List<ExportSpecifier> getSpecifiers() {
        return specifiers;
    }

    public StringLiteral getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }
}
