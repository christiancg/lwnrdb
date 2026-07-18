package org.techhouse.simplejs.nodes;

public class ExportAllDeclaration extends Statement {
    private final Identifier exported;
    private final StringLiteral source;

    public ExportAllDeclaration(Identifier exported, StringLiteral source) {
        this.exported = exported;
        this.source = source;
    }

    public Identifier getExported() {
        return exported;
    }

    public StringLiteral getSource() {
        return source;
    }
}
