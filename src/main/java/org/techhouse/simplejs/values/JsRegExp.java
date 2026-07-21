package org.techhouse.simplejs.values;

import java.util.regex.Pattern;

public final class JsRegExp extends JsValue {
    private final String source;
    private final String flags;
    private final Pattern pattern;
    private int lastIndex;

    public JsRegExp(String source, String flags, Pattern pattern) {
        this.source = source;
        this.flags = flags;
        this.pattern = pattern;
    }

    public String getSource() {
        return source;
    }

    public String getFlags() {
        return flags;
    }

    public Pattern getPattern() {
        return pattern;
    }

    public int getLastIndex() {
        return lastIndex;
    }

    public void setLastIndex(int value) {
        this.lastIndex = value;
    }

    public boolean isGlobal() {
        return flags.indexOf('g') >= 0;
    }

    public boolean isIgnoreCase() {
        return flags.indexOf('i') >= 0;
    }

    public boolean isMultiline() {
        return flags.indexOf('m') >= 0;
    }

    public boolean isDotAll() {
        return flags.indexOf('s') >= 0;
    }

    public boolean isSticky() {
        return flags.indexOf('y') >= 0;
    }

    public boolean hasIndices() {
        return flags.indexOf('d') >= 0;
    }
}
