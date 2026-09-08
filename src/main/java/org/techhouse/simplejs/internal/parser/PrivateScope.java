package org.techhouse.simplejs.internal.parser;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

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
