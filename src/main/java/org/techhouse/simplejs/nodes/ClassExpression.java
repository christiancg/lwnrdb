package org.techhouse.simplejs.nodes;

public class ClassExpression extends Expression {
    private final Identifier id;
    private final Expression superClass;
    private final ClassBody body;

    public ClassExpression(Identifier id, Expression superClass, ClassBody body) {
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
