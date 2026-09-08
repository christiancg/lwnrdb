package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.invalid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class RegexCursor {
    protected final String source;
    protected final boolean unicode;
    protected final boolean unicodeSets;
    protected boolean ignoreCase;
    protected boolean multiline;
    protected boolean dotAll;
    protected final Map<String, List<Integer>> aliases = new LinkedHashMap<>();
    protected int totalGroups;
    protected int groupCounter;
    protected int pos;

    protected RegexCursor(String source, String flags) {
        this.source = source;
        this.unicodeSets = flags.indexOf('v') >= 0;
        this.unicode = flags.indexOf('u') >= 0 || unicodeSets;
        this.ignoreCase = flags.indexOf('i') >= 0;
        this.multiline = flags.indexOf('m') >= 0;
        this.dotAll = flags.indexOf('s') >= 0;
    }

    protected char peek() {
        return source.charAt(pos);
    }

    protected boolean has(int offset) {
        return pos + offset < source.length();
    }

    protected boolean startsWith(String prefix) {
        return source.startsWith(prefix, pos);
    }

    protected CodePointSet foldedSet(CodePointSet raw) {
        return ignoreCase ? CaseFold.widen(raw, unicode) : raw;
    }

    protected void expect(char expected) {
        if (pos >= source.length() || peek() != expected) {
            throw invalid("expected '" + expected + "'");
        }
        pos++;
    }
}
