package org.techhouse.simplejs.internal.parser;

import java.util.HashSet;
import java.util.Set;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

// One lexical scope's declared names, used only for the redeclaration early errors. A var name is
// recorded in every scope it crosses on the way up to its function boundary, so a lexical
// declaration anywhere between the var and that boundary is rejected as the spec requires.
public final class DeclarationScope {
    private final Set<String> lexical = new HashSet<>();
    private final Set<String> vars = new HashSet<>();
    private final DeclarationScope parent;
    private final boolean functionBoundary;

    public DeclarationScope(DeclarationScope parent, boolean functionBoundary) {
        this.parent = parent;
        this.functionBoundary = functionBoundary;
    }

    public DeclarationScope getParent() {
        return parent;
    }

    public boolean isFunctionBoundary() {
        return functionBoundary;
    }

    public void declareLexical(String name) {
        if (!lexical.add(name) || vars.contains(name)) {
            throw alreadyDeclared(name);
        }
    }

    public void declareVar(String name) {
        var scope = this;
        while (scope != null) {
            if (scope.lexical.contains(name)) {
                throw alreadyDeclared(name);
            }
            scope.vars.add(name);
            if (scope.functionBoundary) {
                return;
            }
            scope = scope.parent;
        }
    }

    private static SyntaxErrorException alreadyDeclared(String name) {
        return new SyntaxErrorException("Identifier '" + name + "' has already been declared");
    }
}
