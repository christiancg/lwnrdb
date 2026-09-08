package org.techhouse.simplejs.internal.parser;

import java.util.Map;
import java.util.Set;

public final class ParserTables {
    public static final Set<String> ASSIGNMENT_OPERATORS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=",
            ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=");

    public static final Set<String> PREFIX_UNARY_OPERATORS = Set.of("!", "~", "+", "-");

    public static final Set<String> STRICT_RESERVED = Set.of("implements", "interface", "package", "private",
            "protected", "public");

    public static final Set<String> RESTRICTED_BINDINGS = Set.of("eval", "arguments");

    public static final Set<String> USING_KINDS = Set.of("using", "await using");

    public static final Set<String> ITERATION_KEYWORDS = Set.of("for", "while", "do");

    public static final Set<String> LOGICAL_OPERATORS = Set.of("&&", "||", "??");

    public static final Map<String, Integer> BINARY_PRECEDENCE = Map.ofEntries(Map.entry("??", 1), Map.entry("||", 2),
            Map.entry("&&", 3), Map.entry("|", 4), Map.entry("^", 5), Map.entry("&", 6), Map.entry("==", 7),
            Map.entry("!=", 7), Map.entry("===", 7), Map.entry("!==", 7), Map.entry("<", 8), Map.entry("<=", 8),
            Map.entry(">", 8), Map.entry(">=", 8), Map.entry("instanceof", 8), Map.entry("in", 8), Map.entry("<<", 9),
            Map.entry(">>", 9), Map.entry(">>>", 9), Map.entry("+", 10), Map.entry("-", 10), Map.entry("*", 11),
            Map.entry("/", 11), Map.entry("%", 11), Map.entry("**", 12));

    private ParserTables() {
    }
}
