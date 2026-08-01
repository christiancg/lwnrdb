package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBigInt;
import org.techhouse.simplejs.elements.JsBoolean;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsIdentifier;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsNull;
import org.techhouse.simplejs.elements.JsNumber;
import org.techhouse.simplejs.elements.JsOperator;
import org.techhouse.simplejs.elements.JsPrivateIdentifier;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.JsString;
import org.techhouse.simplejs.elements.JsTemplateString;
import org.techhouse.simplejs.elements.JsUndefined;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.exceptions.UnterminatedStringException;
import org.techhouse.simplejs.exceptions.UnterminatedTemplateException;

public final class Lexer {
    private Lexer() {
    }

    private static final Set<String> JS_KEYWORD = Set.of("if", "do", "while", "for", "in", "of", "switch", "case",
            "default", "var", "let", "const", "break", "continue", "return", "try", "catch", "finally", "throw",
            "async", "await", "yield", "function", "import", "export", "this", "constructor", "new", "class", "else",
            "typeof", "instanceof", "void", "delete", "extends", "super");

    private static final Set<String> EXPRESSION_END_KEYWORDS = Set.of("this", "super");

    private static final Set<Character> SEPARATORS = Set.of('(', ')', '{', '}', '[', ']', ';', ',');

    private static final List<String> OPERATORS = List.of(">>>=", "...", "===", "!==", ">>>", "**=", "<<=", ">>=",
            "&&=", "||=", "??=", "=>", "?.", "==", "!=", "<=", ">=", "&&", "||", "??", "**", "++", "--", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "=", "+", "-", "*", "/", "%", "<", ">", "!", "&", "|", "^",
            "~", "?", ":", ".");

    private record Lexed(JsBaseElement token, int next) {
    }

    // Tokens plus the parallel list of source positions (one per token, EOF included) and the
    // original source. Positions live here rather than on the tokens so the shared
    // JsNull/JsUndefined/JsEOF singletons stay singletons. newlineBefore[i] records whether a
    // line terminator was skipped in the trivia immediately before token i (drives ASI).
    public record LexResult(String source, List<JsBaseElement> tokens, List<SourcePosition> positions,
            List<Boolean> newlineBefore) {
    }

    public static List<JsBaseElement> lex(String sourceCode) {
        return lexWithPositions(sourceCode).tokens();
    }

    public static LexResult lexWithPositions(String sourceCode) {
        final var tokens = new ArrayList<JsBaseElement>();
        final var positions = new ArrayList<SourcePosition>();
        final var newlineBefore = new ArrayList<Boolean>();
        final var lineStarts = computeLineStarts(sourceCode);
        final var n = sourceCode.length();
        var pos = skipHashbang(sourceCode, n);
        var sawNewline = false;
        JsBaseElement last = null;
        while (pos < n) {
            final var c = sourceCode.charAt(pos);
            if (Character.isWhitespace(c)) {
                if (isLineTerminator(c)) {
                    sawNewline = true;
                }
                pos++;
                continue;
            }
            if (c == '/' && pos + 1 < n && (sourceCode.charAt(pos + 1) == '/' || sourceCode.charAt(pos + 1) == '*')) {
                final var commentStart = pos;
                pos = skipComment(sourceCode, pos);
                if (!sawNewline && containsLineTerminator(sourceCode, commentStart, pos)) {
                    sawNewline = true;
                }
                continue;
            }
            final Lexed lexed;
            if (c == '"' || c == '\'') {
                lexed = lexString(sourceCode, pos);
            } else if (c == '`') {
                lexed = lexTemplate(sourceCode, pos);
            } else if (Character.isDigit(c)
                    || (c == '.' && pos + 1 < n && Character.isDigit(sourceCode.charAt(pos + 1)))) {
                lexed = lexNumber(sourceCode, pos);
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                lexed = lexWord(sourceCode, pos);
            } else if (c == '#' && pos + 1 < n && isIdentifierStart(sourceCode.charAt(pos + 1))) {
                lexed = lexPrivateIdentifier(sourceCode, pos);
            } else if (c == '/' && startsRegex(last)) {
                lexed = lexRegex(sourceCode, pos);
            } else {
                final var op = lexOperator(sourceCode, pos);
                if (op != null) {
                    lexed = op;
                } else if (SEPARATORS.contains(c)) {
                    lexed = new Lexed(new JsSeparator(c), pos + 1);
                } else {
                    throw new UnexpectedCharacterException(c, pos);
                }
            }
            tokens.add(lexed.token());
            positions.add(positionOf(pos, lexed.next() - pos, lineStarts));
            newlineBefore.add(sawNewline);
            sawNewline = false;
            last = lexed.token();
            pos = lexed.next();
        }
        tokens.add(JsEOF.getInstance());
        positions.add(positionOf(n, 0, lineStarts));
        newlineBefore.add(sawNewline);
        return new LexResult(sourceCode, tokens, positions, newlineBefore);
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    private static boolean containsLineTerminator(String src, int from, int to) {
        for (var i = from; i < to; i++) {
            if (isLineTerminator(src.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int[] computeLineStarts(String src) {
        final var starts = new ArrayList<Integer>();
        starts.add(0);
        for (var i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static SourcePosition positionOf(int offset, int length, int[] lineStarts) {
        var idx = Arrays.binarySearch(lineStarts, offset);
        if (idx < 0) {
            idx = -idx - 2;
        }
        return new SourcePosition(offset, length, idx + 1, offset - lineStarts[idx] + 1);
    }

    // A `#!` hashbang is only recognised at the very start of the source and is skipped to
    // end-of-line as trivia (no token emitted), like a comment.
    private static int skipHashbang(String src, int n) {
        if (!src.startsWith("#!")) {
            return 0;
        }
        var i = 2;
        while (i < n && src.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipComment(String src, int start) {
        final var n = src.length();
        if (src.charAt(start + 1) == '/') {
            var i = start + 2;
            while (i < n && src.charAt(i) != '\n') {
                i++;
            }
            return i;
        }
        var i = start + 2;
        while (i + 1 < n) {
            if (src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        throw new UnterminatedCommentException(start);
    }

    private static Lexed lexString(String src, int start) {
        final var quote = src.charAt(start);
        final var n = src.length();
        final var builder = new StringBuilder();
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                i = appendEscape(src, i + 1, builder);
            } else if (c == quote) {
                return new Lexed(new JsString(builder.toString()), i + 1);
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedStringException(start);
    }

    private static int appendEscape(String src, int i, StringBuilder builder) {
        final var e = src.charAt(i);
        switch (e) {
            case 'n' -> builder.append('\n');
            case 't' -> builder.append('\t');
            case 'r' -> builder.append('\r');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'v' -> builder.append('\u000B');
            case '0' -> {
                if (i + 1 < src.length() && Character.isDigit(src.charAt(i + 1))) {
                    throw new SyntaxErrorException("Octal escape sequences are not allowed in strict mode");
                }
                builder.append('\0');
            }
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                throw new SyntaxErrorException("Octal escape sequences are not allowed in strict mode");
            case '\n' -> {
                return i + 1;
            }
            case '\r' -> {
                return i + 1 < src.length() && src.charAt(i + 1) == '\n' ? i + 2 : i + 1;
            }
            case 'x' -> {
                if (i + 2 < src.length()) {
                    builder.append((char) Integer.parseInt(src.substring(i + 1, i + 3), 16));
                    return i + 3;
                }
                builder.append(e);
            }
            case 'u' -> {
                return appendUnicodeEscape(src, i, builder);
            }
            default -> builder.append(e);
        }
        return i + 1;
    }

    private static int appendUnicodeEscape(String src, int i, StringBuilder builder) {
        final var n = src.length();
        if (i + 1 < n && src.charAt(i + 1) == '{') {
            final var end = src.indexOf('}', i + 2);
            if (end > 0) {
                builder.appendCodePoint(Integer.parseInt(src.substring(i + 2, end), 16));
                return end + 1;
            }
        } else if (i + 4 < n) {
            builder.append((char) Integer.parseInt(src.substring(i + 1, i + 5), 16));
            return i + 5;
        }
        builder.append('u');
        return i + 1;
    }

    private static Lexed lexNumber(String src, int start) {
        final var n = src.length();
        if (src.charAt(start) == '0' && start + 1 < n) {
            final var radix = switch (Character.toLowerCase(src.charAt(start + 1))) {
                case 'x' -> 16;
                case 'o' -> 8;
                case 'b' -> 2;
                default -> 0;
            };
            if (radix != 0) {
                final var end = scanDigits(src, start + 2, n, radix);
                final var digits = src.substring(start + 2, end).replace("_", "");
                if (end < n && src.charAt(end) == 'n') {
                    return new Lexed(new JsBigInt(new BigInteger(digits, radix)), end + 1);
                }
                return new Lexed(new JsNumber((double) Long.parseLong(digits, radix)), end);
            }
            if (Character.isDigit(src.charAt(start + 1))) {
                throw new SyntaxErrorException("Octal literals are not allowed in strict mode; use the 0o prefix");
            }
        }
        var j = scanDigits(src, start, n, 10);
        final var fractional = j < n && (src.charAt(j) == '.' || src.charAt(j) == 'e' || src.charAt(j) == 'E');
        if (!fractional && j < n && src.charAt(j) == 'n') {
            return new Lexed(new JsBigInt(new BigInteger(src.substring(start, j).replace("_", ""))), j + 1);
        }
        if (j < n && src.charAt(j) == '.') {
            j = scanDigits(src, j + 1, n, 10);
        }
        if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
            var k = j + 1;
            if (k < n && (src.charAt(k) == '+' || src.charAt(k) == '-')) {
                k++;
            }
            if (k < n && Character.isDigit(src.charAt(k))) {
                j = scanDigits(src, k, n, 10);
            }
        }
        return new Lexed(new JsNumber(Double.parseDouble(src.substring(start, j).replace("_", ""))), j);
    }

    // Consumes a run of radix digits, allowing a single '_' separator only between two digits.
    private static int scanDigits(String src, int start, int n, int radix) {
        var j = start;
        while (j < n) {
            final var c = src.charAt(j);
            if (isRadixDigit(c, radix)) {
                j++;
            } else if (c == '_' && j > start && j + 1 < n && isRadixDigit(src.charAt(j + 1), radix)
                    && isRadixDigit(src.charAt(j - 1), radix)) {
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    private static boolean isRadixDigit(char c, int radix) {
        return Character.digit(c, radix) >= 0;
    }

    private static Lexed lexWord(String src, int start) {
        final var n = src.length();
        var i = start;
        while (i < n) {
            final var c = src.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                i++;
            } else {
                break;
            }
        }
        final var word = src.substring(start, i);
        final JsBaseElement token = switch (word) {
            case "true" -> new JsBoolean(true);
            case "false" -> new JsBoolean(false);
            case "null" -> JsNull.getInstance();
            case "undefined" -> JsUndefined.getInstance();
            default -> JS_KEYWORD.contains(word) ? new JsKeyword(word) : new JsIdentifier(word);
        };
        return new Lexed(token, i);
    }

    // A private identifier is a `#` immediately followed by an identifier; the leading `#` is
    // dropped and only the name is stored.
    private static Lexed lexPrivateIdentifier(String src, int start) {
        final var n = src.length();
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                i++;
            } else {
                break;
            }
        }
        return new Lexed(new JsPrivateIdentifier(src.substring(start + 1, i)), i);
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    // A slash begins a regex only when the previous significant token cannot end an
    // expression; otherwise it is the division operator. This is the standard JS lexer
    // heuristic for the regex/division ambiguity.
    private static boolean startsRegex(JsBaseElement last) {
        if (last == null) {
            return true;
        }
        return switch (last.getType()) {
            case OPERATOR -> true;
            case KEYWORD -> !EXPRESSION_END_KEYWORDS.contains(((JsKeyword) last).getValue());
            case SEPARATOR -> {
                final var c = ((JsSeparator) last).getValue();
                yield c != ')' && c != ']' && c != '}';
            }
            default -> false;
        };
    }

    private static Lexed lexRegex(String src, int start) {
        final var n = src.length();
        final var pattern = new StringBuilder();
        var i = start + 1;
        var inClass = false;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                pattern.append(c).append(src.charAt(i + 1));
                i += 2;
            } else if (c == '\n') {
                break;
            } else if (c == '[') {
                inClass = true;
                pattern.append(c);
                i++;
            } else if (c == ']') {
                inClass = false;
                pattern.append(c);
                i++;
            } else if (c == '/' && !inClass) {
                i++;
                final var flags = new StringBuilder();
                while (i < n && Character.isLetter(src.charAt(i))) {
                    flags.append(src.charAt(i));
                    i++;
                }
                return new Lexed(new JsRegex(pattern.toString(), flags.toString()), i);
            } else {
                pattern.append(c);
                i++;
            }
        }
        throw new UnterminatedRegexException(start);
    }

    private static Lexed lexTemplate(String src, int start) {
        final var n = src.length();
        final var quasis = new ArrayList<String>();
        final var rawQuasis = new ArrayList<String>();
        final var expressions = new ArrayList<List<JsBaseElement>>();
        final var builder = new StringBuilder();
        var rawStart = start + 1;
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n) {
                    break;
                }
                i = appendEscape(src, i + 1, builder);
            } else if (c == '`') {
                quasis.add(builder.toString());
                rawQuasis.add(src.substring(rawStart, i));
                return new Lexed(new JsTemplateString(quasis, rawQuasis, expressions), i + 1);
            } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                quasis.add(builder.toString());
                rawQuasis.add(src.substring(rawStart, i));
                builder.setLength(0);
                final var close = scanBalancedBraces(src, i + 2, start);
                expressions.add(lex(src.substring(i + 2, close)));
                i = close + 1;
                rawStart = i;
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedTemplateException(start);
    }

    // Finds the closing brace of a `${ ... }` interpolation, skipping over string and
    // nested template literals so their braces are not miscounted.
    private static int scanBalancedBraces(String src, int from, int templateStart) {
        final var n = src.length();
        var depth = 1;
        var j = from;
        while (j < n) {
            final var c = src.charAt(j);
            switch (c) {
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return j;
                    }
                }
                case '\'', '"' -> j = skipStringLiteral(src, j);
                case '`' -> j = skipTemplateLiteral(src, j, templateStart);
                default -> {
                }
            }
            j++;
        }
        throw new UnterminatedTemplateException(templateStart);
    }

    private static int skipStringLiteral(String src, int start) {
        final var quote = src.charAt(start);
        final var n = src.length();
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i;
            } else {
                i++;
            }
        }
        throw new UnterminatedStringException(start);
    }

    private static int skipTemplateLiteral(String src, int start, int templateStart) {
        final var n = src.length();
        var i = start + 1;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '`') {
                return i;
            } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                i = scanBalancedBraces(src, i + 2, templateStart) + 1;
            } else {
                i++;
            }
        }
        throw new UnterminatedTemplateException(templateStart);
    }

    private static Lexed lexOperator(String src, int start) {
        for (final var op : OPERATORS) {
            if (src.regionMatches(start, op, 0, op.length())) {
                return new Lexed(new JsOperator(op), start + op.length());
            }
        }
        return null;
    }
}
