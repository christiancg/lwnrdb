package org.techhouse.simplejs.nodes;

import java.util.List;

public class VariableDeclaration extends Statement {
    private final String kind;
    private final List<VariableDeclarator> declarations;

    public VariableDeclaration(String kind, List<VariableDeclarator> declarations) {
        this.kind = kind;
        this.declarations = declarations;
    }

    public String getKind() {
        return kind;
    }

    public List<VariableDeclarator> getDeclarations() {
        return declarations;
    }
}
