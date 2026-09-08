package org.techhouse.simplejs.internal.regex;

import java.util.List;
import java.util.Map;

public final class RegexProgram {
    private final RxNode root;
    private final int groupCount;
    private final Map<String, List<Integer>> groupAliases;
    private final boolean unicode;

    RegexProgram(RxNode root, int groupCount, Map<String, List<Integer>> groupAliases, boolean unicode) {
        this.root = root;
        this.groupCount = groupCount;
        this.groupAliases = groupAliases;
        this.unicode = unicode;
    }

    RxNode root() {
        return root;
    }

    int groupCount() {
        return groupCount;
    }

    public Map<String, List<Integer>> groupAliases() {
        return groupAliases;
    }

    boolean unicode() {
        return unicode;
    }
}
