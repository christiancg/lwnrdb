package org.techhouse.simplejs.nodes;

public class RegexLiteral extends Expression {
    private final String pattern;
    private final String flags;

    public RegexLiteral(String pattern, String flags) {
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
