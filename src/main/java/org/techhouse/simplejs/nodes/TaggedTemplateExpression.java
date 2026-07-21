package org.techhouse.simplejs.nodes;

public class TaggedTemplateExpression extends Expression {
    private final Expression tag;
    private final TemplateLiteral quasi;

    public TaggedTemplateExpression(Expression tag, TemplateLiteral quasi) {
        this.tag = tag;
        this.quasi = quasi;
    }

    public Expression getTag() {
        return tag;
    }

    public TemplateLiteral getQuasi() {
        return quasi;
    }
}
