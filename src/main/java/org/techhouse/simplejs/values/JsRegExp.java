package org.techhouse.simplejs.values;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class JsRegExp extends JsValue {
    private static final String LAST_INDEX = "lastIndex";

    private PropertyTable table;

    private final String source;
    private final String flags;
    private final Pattern pattern;
    // Original group name -> the java group names it was compiled to; more than one when ES2025
    // duplicate named groups appear in different alternatives.
    private final Map<String, List<String>> groupAliases;

    public JsRegExp(String source, String flags, Pattern pattern) {
        this(source, flags, pattern, Map.of());
    }

    public JsRegExp(String source, String flags, Pattern pattern, Map<String, List<String>> groupAliases) {
        this.source = source;
        this.flags = flags;
        this.pattern = pattern;
        this.groupAliases = groupAliases == null ? Map.of() : groupAliases;
        // lastIndex is an ordinary own data property, not an internal slot behind an accessor: a
        // script may redefine it non-writable, and RegExpBuiltinExec's Set must then throw.
        final var table = ownProperties();
        table.defineValue(LAST_INDEX, new JsNumber(0));
        table.setFlags(LAST_INDEX, new JsObject.PropertyFlags(true, false, false));
    }

    public Map<String, List<String>> getGroupAliases() {
        return groupAliases;
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

    public JsValue getLastIndex() {
        return ownProperties().get(LAST_INDEX);
    }

    public void setLastIndex(int value) {
        ownProperties().defineValue(LAST_INDEX, new JsNumber(value));
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

    public boolean isUnicode() {
        return flags.indexOf('u') >= 0;
    }

    public boolean isUnicodeSets() {
        return flags.indexOf('v') >= 0;
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
