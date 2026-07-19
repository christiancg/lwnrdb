package org.techhouse.simplejs.nodes;

import java.util.List;

public class ExportAllDeclaration extends Statement {
    private final Identifier exported;
    private final StringLiteral source;
    private final List<ImportAttribute> attributes;

    public ExportAllDeclaration(Identifier exported, StringLiteral source, List<ImportAttribute> attributes) {
        this.exported = exported;
        this.source = source;
        this.attributes = attributes;
    }

    public Identifier getExported() {
        return exported;
    }

    public StringLiteral getSource() {
        return source;
    }

    public List<ImportAttribute> getAttributes() {
        return attributes;
    }
}
