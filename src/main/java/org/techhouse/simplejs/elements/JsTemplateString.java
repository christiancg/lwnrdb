package org.techhouse.simplejs.elements;

import java.util.List;

public class JsTemplateString extends JsBaseElement {
    private final List<String> quasis;
    private final List<List<JsBaseElement>> expressions;
    public JsTemplateString(List<String> quasis, List<List<JsBaseElement>> expressions) {
        if (quasis.size() != expressions.size() + 1) {
            throw new IllegalArgumentException("A template literal must have exactly one more quasi than expressions");
        }
        this.quasis = quasis;
        this.expressions = expressions;
    }
    public List<String> getQuasis() {
        return quasis;
    }
    public List<List<JsBaseElement>> getExpressions() {
        return expressions;
    }
}
