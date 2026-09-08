package org.techhouse.simplejs.internal;

import static org.techhouse.simplejs.internal.lexer.CharClasses.containsLineTerminator;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isIdentifierStart;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isLineTerminator;
import static org.techhouse.simplejs.internal.lexer.CharClasses.isWhiteSpace;
import static org.techhouse.simplejs.internal.lexer.IdentifierLexer.lexPrivateIdentifier;
import static org.techhouse.simplejs.internal.lexer.IdentifierLexer.lexWord;
import static org.techhouse.simplejs.internal.lexer.LexerTables.EXPRESSION_END_KEYWORDS;
import static org.techhouse.simplejs.internal.lexer.LexerTables.SEPARATORS;
import static org.techhouse.simplejs.internal.lexer.LexerTables.lexOperator;
import static org.techhouse.simplejs.internal.lexer.NumberLexer.lexNumber;
import static org.techhouse.simplejs.internal.lexer.StringLexer.lexString;
import static org.techhouse.simplejs.internal.lexer.TemplateLexer.lexTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.techhouse.simplejs.elements.JsBaseElement;
import org.techhouse.simplejs.elements.JsEOF;
import org.techhouse.simplejs.elements.JsKeyword;
import org.techhouse.simplejs.elements.JsRegex;
import org.techhouse.simplejs.elements.JsSeparator;
import org.techhouse.simplejs.elements.SourcePosition;
import org.techhouse.simplejs.exceptions.UnexpectedCharacterException;
import org.techhouse.simplejs.exceptions.UnterminatedCommentException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.internal.lexer.BraceContext;
import org.techhouse.simplejs.internal.lexer.Lexed;

public final class Lexer {
    private Lexer() {
    }

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

    public static Lexed scanToken(String src, int pos, JsBaseElement last, BraceContext braces) {
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

    public static int skipComment(String src, int start) {
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
}
