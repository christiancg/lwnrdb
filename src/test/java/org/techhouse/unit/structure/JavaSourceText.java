package org.techhouse.unit.structure;

import java.util.ArrayList;
import java.util.List;

final class JavaSourceText {
    static final List<String> DIRECTIVE_PREFIXES = List.of("// NOPMD", "// CHECKSTYLE:", "// @formatter:",
            "//noinspection");
    private static final String TEXT_BLOCK_QUOTES = "\"\"\"";

    private final String source;
    private final List<SourceComment> comments = new ArrayList<>();
    private final List<String> stringLiterals = new ArrayList<>();
    private final char[] code;
    private final List<Integer> lineStarts = new ArrayList<>();

    record SourceComment(int line, String text) {
        boolean isToolchainDirective() {
            return DIRECTIVE_PREFIXES.stream().anyMatch(text::startsWith);
        }
    }

    private JavaSourceText(String source) {
        this.source = source;
        this.code = source.toCharArray();
        indexLines();
        lex();
    }

    static JavaSourceText of(String source) {
        return new JavaSourceText(source);
    }

    List<SourceComment> comments() {
        return comments;
    }

    List<String> stringLiterals() {
        return stringLiterals;
    }

    String code() {
        return new String(code);
    }

    int lineOf(int offset) {
        var low = 0;
        var high = lineStarts.size() - 1;
        while (low < high) {
            final var mid = (low + high + 1) >>> 1;
            if (lineStarts.get(mid) <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low + 1;
    }

    private void indexLines() {
        lineStarts.add(0);
        for (var i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lineStarts.add(i + 1);
            }
        }
    }

    private void lex() {
        var i = 0;
        while (i < source.length()) {
            if (source.startsWith(TEXT_BLOCK_QUOTES, i)) {
                i = textBlock(i);
            } else if (source.charAt(i) == '"') {
                i = quoted(i, '"', true);
            } else if (source.charAt(i) == '\'') {
                i = quoted(i, '\'', false);
            } else if (source.startsWith("//", i)) {
                i = lineComment(i);
            } else if (source.startsWith("/*", i)) {
                i = blockComment(i);
            } else {
                i++;
            }
        }
    }

    private int textBlock(int start) {
        var i = start + TEXT_BLOCK_QUOTES.length();
        while (i < source.length() && !source.startsWith(TEXT_BLOCK_QUOTES, i)) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        final var end = Math.min(source.length(), i + TEXT_BLOCK_QUOTES.length());
        stringLiterals.add(source.substring(start + TEXT_BLOCK_QUOTES.length(), Math.min(i, source.length())));
        blank(start, end);
        return end;
    }

    private int quoted(int start, char quote, boolean isString) {
        var i = start + 1;
        while (i < source.length() && source.charAt(i) != quote && source.charAt(i) != '\n') {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        final var end = Math.min(source.length(), i + 1);
        if (isString) {
            stringLiterals.add(unescape(source.substring(start + 1, Math.min(i, source.length()))));
        }
        blank(start, end);
        return end;
    }

    private int lineComment(int start) {
        final var newline = source.indexOf('\n', start);
        final var end = newline == -1 ? source.length() : newline;
        comments.add(new SourceComment(lineOf(start), source.substring(start, end).strip()));
        blank(start, end);
        return end;
    }

    private int blockComment(int start) {
        final var close = source.indexOf("*/", start + 2);
        final var end = close == -1 ? source.length() : close + 2;
        comments.add(new SourceComment(lineOf(start), source.substring(start, end).strip()));
        blank(start, end);
        return end;
    }

    private void blank(int start, int end) {
        for (var i = start; i < end; i++) {
            if (code[i] != '\n' && code[i] != '\r') {
                code[i] = ' ';
            }
        }
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
