package org.techhouse.simplejs.internal.parser;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

// One class body's private environment, used for the AllPrivateNamesValid early error. Every
// `#name` a class body declares is in scope for the whole body, so a reference cannot be resolved
// where it is read: references are collected and matched against the declared names when the body
// closes, and whatever is still unresolved is handed to the enclosing class body (a class nested in
// a method may legally use the outer class's names). A reference left over at the outermost class
// body has no declaration anywhere and is a Syntax Error.
public final class PrivateScope {
    private final Set<String> declared = new HashSet<>();
    private final Set<String> referenced = new LinkedHashSet<>();
    private final PrivateScope parent;

    public PrivateScope(PrivateScope parent) {
        this.parent = parent;
    }

    public PrivateScope getParent() {
        return parent;
    }

    public void declare(String name) {
        declared.add(name);
    }

    public void reference(String name) {
        referenced.add(name);
    }

    public void resolve() {
        for (final var name : referenced) {
            if (declared.contains(name)) {
                continue;
            }
            if (parent == null) {
                throw undeclared(name);
            }
            parent.reference(name);
        }
    }

    public static SyntaxErrorException undeclared(String name) {
        return new SyntaxErrorException("Private field '#" + name + "' must be declared in an enclosing class");
    }
}
