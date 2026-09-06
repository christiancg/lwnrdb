package org.techhouse.simplejs.values;

import java.util.List;
import java.util.Map;
import org.techhouse.simplejs.internal.regex.RegexProgram;

public final class JsRegExp extends JsValue {
    private static final String LAST_INDEX = "lastIndex";

    private PropertyTable table;

    private final String source;
    private final String flags;
    private final RegexProgram program;

    public JsRegExp(String source, String flags, RegexProgram program) {
        this.source = source;
        this.flags = flags;
        this.program = program;
        // lastIndex is an ordinary own data property, not an internal slot behind an accessor: a
        // script may redefine it non-writable, and RegExpBuiltinExec's Set must then throw.
        final var table = ownProperties();
        table.defineValue(LAST_INDEX, new JsNumber(0));
        table.setFlags(LAST_INDEX, new JsObject.PropertyFlags(true, false, false));
    }

    // Original group name -> the capturing group numbers it was compiled to; more than one when
    // ES2025 duplicate named groups appear in different alternatives.
    public Map<String, List<Integer>> getGroupAliases() {
        return program.groupAliases();
    }

    public String getSource() {
        return source;
    }

    public String getFlags() {
        return flags;
    }

    public RegexProgram getProgram() {
        return program;
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
