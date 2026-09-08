package org.techhouse.simplejs.elements;

import java.util.List;

public class JsTemplateString extends JsBaseElement {
    private final List<String> quasis;
    private final List<String> rawQuasis;
    private final List<List<JsBaseElement>> expressions;
    public JsTemplateString(List<String> quasis, List<String> rawQuasis, List<List<JsBaseElement>> expressions) {
        if (quasis.size() != expressions.size() + 1) {
            throw new IllegalArgumentException("A template literal must have exactly one more quasi than expressions");
        }
        if (rawQuasis.size() != quasis.size()) {
            throw new IllegalArgumentException("A template literal must have one raw quasi per cooked quasi");
        }
        this.quasis = quasis;
        this.rawQuasis = rawQuasis;
        this.expressions = expressions;
    }
    public List<String> getQuasis() {
        return quasis;
    }
    public List<String> getRawQuasis() {
        return rawQuasis;
    }
    public List<List<JsBaseElement>> getExpressions() {
        return expressions;
    }
}
