package org.techhouse.simplejs.nodes;

public class PrivateIdentifier extends Expression {
    private final String name;

    public PrivateIdentifier(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
