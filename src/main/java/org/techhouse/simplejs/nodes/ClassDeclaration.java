package org.techhouse.simplejs.nodes;

public class ClassDeclaration extends Statement {
    private final Identifier id;
    private final Expression superClass;
    private final ClassBody body;

    public ClassDeclaration(Identifier id, Expression superClass, ClassBody body) {
        this.id = id;
        this.superClass = superClass;
        this.body = body;
    }

    public Identifier getId() {
        return id;
    }

    public Expression getSuperClass() {
        return superClass;
    }

    public ClassBody getBody() {
        return body;
    }
}
