package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.validateFlags;

import java.util.List;
import java.util.Map;

public final class RegexParser {

    private RegexParser() {
    }

    public static RegexProgram compile(String source, String flags) {
        validateFlags(flags);
        final var parser = new RegexProductions(source, flags);
        return parser.parse();
    }

    public static Map<String, List<Integer>> aliasesOf(RegexProgram program) {
        return program.groupAliases();
    }

}
