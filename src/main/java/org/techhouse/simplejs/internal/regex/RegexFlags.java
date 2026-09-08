package org.techhouse.simplejs.internal.regex;

import java.util.List;
import java.util.Set;

public final class RegexFlags {
    public static final String VALID_FLAGS = "dgimsuvy";
    public static final String LAST_INDEX = "lastIndex";

    public static final List<String> ACCESSOR_NAMES = List.of("source", "flags", "global", "ignoreCase", "multiline",
            "dotAll", "sticky", "hasIndices", "unicode", "unicodeSets");
    public static final Set<String> NON_FLAGS_ACCESSOR_NAMES = Set.of("source", "global", "ignoreCase", "multiline",
            "dotAll", "sticky", "hasIndices", "unicode", "unicodeSets");
    public static final List<String> FLAG_ACCESSOR_NAMES = List.of("hasIndices", "global", "ignoreCase", "multiline",
            "dotAll", "unicode", "unicodeSets", "sticky");

    private RegexFlags() {
    }
}
