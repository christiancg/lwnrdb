package org.techhouse.simplejs.elements;

public class JsRegex extends JsBaseElement {
    private final String pattern;
    private final String flags;
    public JsRegex(String pattern, String flags) {
        this.pattern = pattern;
        this.flags = flags;
    }
    public String getPattern() {
        return pattern;
    }
    public String getFlags() {
        return flags;
    }
}
