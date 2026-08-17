package org.techhouse.simplejs.internal;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsBaseElement.JsType;
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
            "async", "await", "yield", "function", "import", "export", "this", "new", "class", "else", "typeof",
            "instanceof", "void", "delete", "extends", "super");

    // Reserved words a unicode escape may not spell: the literals plus the words the lexer keeps
    // contextual or does not support, plus the strict future-reserved words.
    private static final Set<String> ESCAPE_RESERVED = Set.of("true", "false", "null", "debugger", "enum", "with",
            "implements", "interface", "package", "private", "protected", "public", "static");

    private static final Set<String> EXPRESSION_END_KEYWORDS = Set.of("this", "super");

    private static final char ZWNJ = 0x200C;

    private static final char ZWJ = 0x200D;

    // A modifier letter to the JDK, but Pattern_Syntax to Unicode, so ES excludes it from identifiers.
    private static final char VERTICAL_TILDE = 0x2E2F;

    private static final Set<Character> SEPARATORS = Set.of('(', ')', '{', '}', '[', ']', ';', ',');

    private static final List<String> OPERATORS = List.of(">>>=", "...", "===", "!==", ">>>", "**=", "<<=", ">>=",
            "&&=", "||=", "??=", "=>", "?.", "==", "!=", "<=", ">=", "&&", "||", "??", "**", "++", "--", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>", "=", "+", "-", "*", "/", "%", "<", ">", "!", "&", "|", "^",
            "~", "?", ":", ".");

    private record Lexed(JsBaseElement token, int next) {
    }

    private record IdentifierScan(String name, int next, boolean escaped) {
    }

    private record EscapePoint(int point, int next) {
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
        final var braces = new BraceContext();
        JsBaseElement last = null;
        while (pos < n) {
            final var c = sourceCode.charAt(pos);
            if (isWhiteSpace(c)) {
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
            final var lexed = scanToken(sourceCode, pos, last, braces);
            braces.observe(lexed.token(), last);
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

    // The one token scanner: both the top level and a template substitution go through it, so the
    // regex-versus-division decision (and every other lexical rule) cannot diverge between the two.
    private static Lexed scanToken(String src, int pos, JsBaseElement last, BraceContext braces) {
        final var n = src.length();
        final var c = src.charAt(pos);
        if (c == '"' || c == '\'') {
            return lexString(src, pos);
        }
        if (c == '`') {
            return lexTemplate(src, pos);
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(src.charAt(pos + 1)))) {
            return lexNumber(src, pos);
        }
        if (isIdentifierStart(src, pos)) {
            return lexWord(src, pos);
        }
        if (c == '#' && pos + 1 < n && isIdentifierStart(src, pos + 1)) {
            return lexPrivateIdentifier(src, pos);
        }
        if (c == '/' && startsRegex(last, braces)) {
            return lexRegex(src, pos);
        }
        final var op = lexOperator(src, pos);
        if (op != null) {
            return op;
        }
        if (SEPARATORS.contains(c)) {
            return new Lexed(new JsSeparator(c), pos + 1);
        }
        throw new UnexpectedCharacterException(c, pos);
    }

    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    // Spec WhiteSpace plus LineTerminator: Character.isWhitespace disagrees at both ends - it rejects
    // the non-breaking spaces (NBSP, ZWNBSP, and the narrow/figure spaces) and accepts the C0
    // separators, which are not JS whitespace.
    private static boolean isWhiteSpace(char c) {
        return c == '\t' || c == '\u000B' || c == '\f' || c == ' ' || c == '\u00A0' || c == '\uFEFF'
                || Character.getType(c) == Character.SPACE_SEPARATOR || isLineTerminator(c);
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
        return skipToLineTerminator(src, 2, n);
    }

    private static int skipToLineTerminator(String src, int start, int n) {
        var i = start;
        while (i < n && !isLineTerminator(src.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipComment(String src, int start) {
        final var n = src.length();
        if (src.charAt(start + 1) == '/') {
            return skipToLineTerminator(src, start + 2, n);
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
            } else if (c == '\n' || c == '\r') {
                // LineSeparator and ParagraphSeparator are legal in a string literal; LF and CR
                // are not - the literal has to be closed on the line it opened on.
                break;
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedStringException(start);
    }

    private static int appendEscape(String src, int i, StringBuilder builder) {
        final var e = src.charAt(i);
        if (isLineTerminator(e)) {
            return e == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n' ? i + 2 : i + 1;
        }
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
            case 'x' -> {
                builder.append((char) readHex(src, i + 1, 2));
                return i + 3;
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
            if (end < 0) {
                throw new SyntaxErrorException("Invalid Unicode escape sequence");
            }
            final var point = readHex(src, i + 2, end - i - 2);
            if (point > Character.MAX_CODE_POINT) {
                throw new SyntaxErrorException("Undefined Unicode code-point");
            }
            builder.appendCodePoint(point);
            return end + 1;
        }
        builder.append((char) readHex(src, i + 1, 4));
        return i + 5;
    }

    // Reads exactly `count` hexadecimal digits: anything shorter, or a non-hex character anywhere
    // in the run (a numeric separator included), makes the escape sequence a Syntax Error.
    private static int readHex(String src, int from, int count) {
        if (count <= 0 || from + count > src.length()) {
            throw new SyntaxErrorException("Invalid hexadecimal escape sequence");
        }
        var value = 0;
        for (var i = from; i < from + count; i++) {
            final var digit = Character.digit(src.charAt(i), 16);
            if (digit < 0) {
                throw new SyntaxErrorException("Invalid hexadecimal escape sequence");
            }
            value = value * 16 + digit;
            if (value > Character.MAX_CODE_POINT) {
                throw new SyntaxErrorException("Undefined Unicode code-point");
            }
        }
        return value;
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
                if (digits.isEmpty()) {
                    throw new SyntaxErrorException("Missing digits after the numeric literal prefix");
                }
                if (end < n && src.charAt(end) == 'n') {
                    return endOfNumber(new JsBigInt(new BigInteger(digits, radix)), src, end + 1);
                }
                return endOfNumber(new JsNumber((double) Long.parseLong(digits, radix)), src, end);
            }
            if (Character.isDigit(src.charAt(start + 1))) {
                throw new SyntaxErrorException("Octal literals are not allowed in strict mode; use the 0o prefix");
            }
            if (src.charAt(start + 1) == '_') {
                throw new SyntaxErrorException("Numeric separators are not allowed after a leading zero");
            }
        }
        var j = scanDigits(src, start, n, 10);
        final var fractional = j < n && (src.charAt(j) == '.' || src.charAt(j) == 'e' || src.charAt(j) == 'E');
        if (!fractional && j < n && src.charAt(j) == 'n') {
            return endOfNumber(new JsBigInt(new BigInteger(src.substring(start, j).replace("_", ""))), src, j + 1);
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
        return endOfNumber(new JsNumber(Double.parseDouble(src.substring(start, j).replace("_", ""))), src, j);
    }

    // A numeric literal must not be followed immediately by an identifier or another digit, so
    // `3in[]` and `0b2` are Syntax Errors rather than a literal plus a token.
    private static Lexed endOfNumber(JsBaseElement token, String src, int end) {
        if (end < src.length() && (Character.isDigit(src.charAt(end)) || isIdentifierStart(src, end))) {
            throw new SyntaxErrorException("Identifier or digit directly after a numeric literal");
        }
        return new Lexed(token, end);
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

    // Every word a unicode escape may not spell: the real keywords plus the literals and the
    // words kept contextual or unsupported (see ESCAPE_RESERVED).
    public static boolean isReservedWord(String word) {
        return JS_KEYWORD.contains(word) || ESCAPE_RESERVED.contains(word);
    }

    private static Lexed lexWord(String src, int start) {
        final var scan = scanIdentifier(src, start);
        final var word = scan.name();
        // An escape sequence never forms a keyword, so an escaped `break` lexes as an identifier
        // named "break". Whether that is legal is position-dependent - fine as an IdentifierName
        // (`obj.break`, a property key, a class member name), a SyntaxError as a binding or
        // reference - so the decision belongs to the parser, which alone knows the position.
        if (scan.escaped() && (JS_KEYWORD.contains(word) || ESCAPE_RESERVED.contains(word))) {
            return new Lexed(new JsIdentifier(word, true), scan.next());
        }
        final JsBaseElement token = switch (word) {
            case "true" -> new JsBoolean(true);
            case "false" -> new JsBoolean(false);
            case "null" -> JsNull.getInstance();
            case "undefined" -> JsUndefined.getInstance();
            default -> JS_KEYWORD.contains(word) ? new JsKeyword(word) : new JsIdentifier(word, scan.escaped());
        };
        return new Lexed(token, scan.next());
    }

    // A private identifier is a `#` immediately followed by an identifier; the leading `#` is
    // dropped and only the name is stored.
    private static Lexed lexPrivateIdentifier(String src, int start) {
        final var scan = scanIdentifier(src, start + 1);
        return new Lexed(new JsPrivateIdentifier(scan.name()), scan.next());
    }

    // Walks the cooked identifier: every position is either an identifier code point or a unicode
    // escape whose decoded code point must itself be valid there.
    private static IdentifierScan scanIdentifier(String src, int start) {
        final var n = src.length();
        final var builder = new StringBuilder();
        var i = start;
        var escaped = false;
        while (i < n) {
            if (src.charAt(i) == '\\') {
                final var decoded = decodeIdentifierEscape(src, i);
                if (isNotValidAt(decoded.point(), builder.isEmpty())) {
                    throw new UnexpectedCharacterException('\\', i);
                }
                builder.appendCodePoint(decoded.point());
                i = decoded.next();
                escaped = true;
                continue;
            }
            final var point = src.codePointAt(i);
            if (isNotValidAt(point, builder.isEmpty())) {
                break;
            }
            builder.appendCodePoint(point);
            i += Character.charCount(point);
        }
        return new IdentifierScan(builder.toString(), i, escaped);
    }

    private static EscapePoint decodeIdentifierEscape(String src, int i) {
        final var n = src.length();
        if (i + 1 >= n || src.charAt(i + 1) != 'u') {
            throw new UnexpectedCharacterException('\\', i);
        }
        if (i + 2 < n && src.charAt(i + 2) == '{') {
            final var end = src.indexOf('}', i + 3);
            if (end < 0) {
                throw new UnexpectedCharacterException('\\', i);
            }
            return new EscapePoint(parseCodePoint(src.substring(i + 3, end), i), end + 1);
        }
        if (i + 6 > n) {
            throw new UnexpectedCharacterException('\\', i);
        }
        return new EscapePoint(parseCodePoint(src.substring(i + 2, i + 6), i), i + 6);
    }

    private static int parseCodePoint(String digits, int offset) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException ignored) {
            throw new UnexpectedCharacterException('\\', offset);
        }
    }

    private static boolean isNotValidAt(int point, boolean atStart) {
        return atStart ? !isIdentifierStartPoint(point) : !isIdentifierPartPoint(point);
    }

    private static boolean isIdentifierStartPoint(int point) {
        return (Character.isUnicodeIdentifierStart(point) && point != VERTICAL_TILDE) || point == '$' || point == '_';
    }

    // The JDK counts every format character as an identifier part; ES admits only ZWNJ and ZWJ.
    private static boolean isIdentifierPartPoint(int point) {
        if (point == '$' || point == ZWNJ || point == ZWJ) {
            return true;
        }
        return Character.isUnicodeIdentifierPart(point) && !Character.isIdentifierIgnorable(point)
                && point != VERTICAL_TILDE;
    }

    private static boolean isIdentifierStart(String src, int pos) {
        final var c = src.charAt(pos);
        if (c == '\\') {
            return pos + 1 < src.length() && src.charAt(pos + 1) == 'u';
        }
        return isIdentifierStartPoint(src.codePointAt(pos));
    }

    // A slash begins a regex only when the previous significant token cannot end an
    // expression; otherwise it is the division operator. This is the standard JS lexer
    // heuristic for the regex/division ambiguity.
    private static boolean startsRegex(JsBaseElement last, BraceContext braces) {
        if (last == null) {
            return true;
        }
        return switch (last.getType()) {
            case OPERATOR -> true;
            case KEYWORD -> !EXPRESSION_END_KEYWORDS.contains(((JsKeyword) last).getValue());
            case SEPARATOR -> {
                final var c = ((JsSeparator) last).getValue();
                yield c == '}' ? braces.closedBlock() : c != ')' && c != ']';
            }
            default -> false;
        };
    }

    // Tracks what each open brace encloses so a `/` after `}` can be told apart: a block, a
    // function-declaration body or a class body ends a statement, and the next `/` therefore starts
    // a regular expression, while an object literal or a function-expression body ends a value, and
    // the next `/` is division.
    private static final class BraceContext {
        private static final Set<String> BLOCK_KEYWORDS = Set.of("else", "do", "try", "finally");

        private static final Set<String> HEADER_KEYWORDS = Set.of("if", "for", "while", "switch", "catch");

        private final Deque<Boolean> open = new ArrayDeque<>();
        private int bodyNesting = -1;
        private boolean bodyIsStatement;
        private boolean closedBlock;
        private boolean closedHeader;

        private void observe(JsBaseElement token, JsBaseElement previous) {
            if (token.getType() == JsType.KEYWORD) {
                observeKeyword(((JsKeyword) token).getValue(), previous);
                return;
            }
            if (token.getType() != JsType.SEPARATOR) {
                return;
            }
            switch (((JsSeparator) token).getValue()) {
                case '(' -> open.push(isHeader(previous));
                case '[' -> open.push(Boolean.FALSE);
                case ')' -> closedHeader = pop();
                case ']' -> pop();
                case '{' -> {
                    open.push(bodyNesting == open.size() ? bodyIsStatement : startsStatement(previous));
                    bodyNesting = -1;
                }
                case '}' -> closedBlock = pop();
                default -> {
                }
            }
        }

        // A `function` or `class` in statement position has a body that ends a statement; the same
        // keyword in expression position has one that ends a value. The decision is taken at the
        // keyword and applied to the brace that opens the body, whatever comes between.
        private void observeKeyword(String keyword, JsBaseElement previous) {
            if ("function".equals(keyword) || "class".equals(keyword)) {
                bodyNesting = open.size();
                bodyIsStatement = startsStatement(previous);
            }
        }

        private boolean isHeader(JsBaseElement previous) {
            return previous != null && previous.getType() == JsType.KEYWORD
                    && HEADER_KEYWORDS.contains(((JsKeyword) previous).getValue());
        }

        private boolean pop() {
            return !open.isEmpty() && Boolean.TRUE.equals(open.pop());
        }

        private boolean closedBlock() {
            return closedBlock;
        }

        private boolean startsStatement(JsBaseElement previous) {
            if (previous == null) {
                return true;
            }
            if (previous.getType() == JsType.KEYWORD) {
                return BLOCK_KEYWORDS.contains(((JsKeyword) previous).getValue());
            }
            if (previous.getType() != JsType.SEPARATOR) {
                return false;
            }
            final var c = ((JsSeparator) previous).getValue();
            return c == ';' || c == '{' || (c == ')' && closedHeader) || (c == '}' && closedBlock);
        }
    }

    private static Lexed lexRegex(String src, int start) {
        final var n = src.length();
        final var pattern = new StringBuilder();
        var i = start + 1;
        var inClass = false;
        while (i < n) {
            final var c = src.charAt(i);
            if (c == '\\') {
                if (i + 1 >= n || isLineTerminator(src.charAt(i + 1))) {
                    break;
                }
                pattern.append(c).append(src.charAt(i + 1));
                i += 2;
            } else if (isLineTerminator(c)) {
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
                rawQuasis.add(normalizeLineTerminators(src.substring(rawStart, i)));
                return new Lexed(new JsTemplateString(quasis, rawQuasis, expressions), i + 1);
            } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                quasis.add(builder.toString());
                rawQuasis.add(normalizeLineTerminators(src.substring(rawStart, i)));
                builder.setLength(0);
                final var substitution = lexSubstitution(src, i + 2, start);
                expressions.add(substitution.tokens());
                i = substitution.close() + 1;
                rawStart = i;
            } else if (c == '\r') {
                builder.append('\n');
                i += i + 1 < n && src.charAt(i + 1) == '\n' ? 2 : 1;
            } else {
                builder.append(c);
                i++;
            }
        }
        throw new UnterminatedTemplateException(start);
    }

    // A template's TV and TRV normalise every <CR> and <CR><LF> sequence to a single <LF>.
    private static String normalizeLineTerminators(String raw) {
        if (raw.indexOf('\r') < 0) {
            return raw;
        }
        return raw.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record Substitution(int close, List<JsBaseElement> tokens) {
    }

    // Lexes a `${ ... }` interpolation with the main token scanner and stops at the brace that closes
    // it: a string, comment, regex or nested template inside the substitution is consumed as one
    // token, so its braces are never miscounted.
    private static Substitution lexSubstitution(String src, int from, int templateStart) {
        final var n = src.length();
        final var tokens = new ArrayList<JsBaseElement>();
        final var braces = new BraceContext();
        JsBaseElement last = null;
        var depth = 1;
        var j = from;
        while (j < n) {
            final var c = src.charAt(j);
            if (isWhiteSpace(c)) {
                j++;
                continue;
            }
            if (c == '/' && j + 1 < n && (src.charAt(j + 1) == '/' || src.charAt(j + 1) == '*')) {
                j = skipComment(src, j);
                continue;
            }
            final var lexed = scanToken(src, j, last, braces);
            final var token = lexed.token();
            if (token.getType() == JsType.SEPARATOR) {
                final var separator = ((JsSeparator) token).getValue();
                if (separator == '{') {
                    depth++;
                } else if (separator == '}' && --depth == 0) {
                    tokens.add(JsEOF.getInstance());
                    return new Substitution(j, tokens);
                }
            }
            braces.observe(token, last);
            tokens.add(token);
            last = token;
            j = lexed.next();
        }
        throw new UnterminatedTemplateException(templateStart);
    }

    private static Lexed lexOperator(String src, int start) {
        for (final var op : OPERATORS) {
            if (!src.regionMatches(start, op, 0, op.length())) {
                continue;
            }
            // `?.` is not the optional-chaining punctuator when a decimal digit follows, so
            // `a ?.3 : b` stays a conditional expression with a fractional literal.
            final var isOptionalChainBeforeDigit = "?.".equals(op) && start + 2 < src.length()
                    && Character.isDigit(src.charAt(start + 2));
            if (!isOptionalChainBeforeDigit) {
                return new Lexed(new JsOperator(op), start + op.length());
            }
        }
        return null;
    }
}
