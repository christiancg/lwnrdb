package org.techhouse.simplejs.internal.lexer;

import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsOperator;

public final class LexerTables {
    public static final Set<String> JS_KEYWORD = Set.of("if", "do", "while", "for", "in", "of", "switch", "case",
            "default", "var", "let", "const", "break", "continue", "return", "try", "catch", "finally", "throw",
            "async", "await", "yield", "function", "import", "export", "this", "new", "class", "else", "typeof",
            "instanceof", "void", "delete", "extends", "super");

    public static final Set<String> ESCAPE_RESERVED = Set.of("true", "false", "null", "debugger", "enum", "with",
            "implements", "interface", "package", "private", "protected", "public", "static");

    public static final Set<String> EXPRESSION_END_KEYWORDS = Set.of("this", "super", "of");

    public static final char ZWNJ = 0x200C;

    public static final char ZWJ = 0x200D;

    public static final char VERTICAL_TILDE = 0x2E2F;

    public static final Set<Character> SEPARATORS = Set.of('(', ')', '{', '}', '[', ']', ';', ',');

    public static final List<String> OPERATORS = List.of(">>>=", "...", "===", "!==", ">>>", "**=", "<<=", ">>=", "&&=",
            "||=", "??=", "=>", "?.", "==", "!=", "<=", ">=", "&&", "||", "??", "**", "++", "--", "+=", "-=", "*=",
            "/=", "%=", "&=", "|=", "^=", "<<", ">>", "=", "+", "-", "*", "/", "%", "<", ">", "!", "&", "|", "^", "~",
            "?", ":", ".");

    public static boolean isReservedWord(String word) {
        return JS_KEYWORD.contains(word) || ESCAPE_RESERVED.contains(word);
    }

    public static Lexed lexOperator(String src, int start) {
        for (final var op : OPERATORS) {
            if (!src.regionMatches(start, op, 0, op.length())) {
                continue;
            }
            final var isOptionalChainBeforeDigit = "?.".equals(op) && start + 2 < src.length()
                    && Character.isDigit(src.charAt(start + 2));
            if (!isOptionalChainBeforeDigit) {
                return new Lexed(new JsOperator(op), start + op.length());
            }
        }
        return null;
    }

    private LexerTables() {
    }
}
