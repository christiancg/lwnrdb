package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.MAX_REPETITION;
import static org.techhouse.simplejs.internal.regex.RegexTables.SYNTAX_CHARACTERS;
import static org.techhouse.simplejs.internal.regex.RegexTables.invalid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

abstract class EscapeReader extends RegexCursor {
    protected EscapeReader(String source, String flags) {
        super(source, flags);
    }

    protected static String decodeGroupName(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        final var out = new StringBuilder();
        var i = 0;
        while (i < raw.length()) {
            if (raw.charAt(i) != '\\' || i + 1 >= raw.length() || raw.charAt(i + 1) != 'u') {
                out.append(raw.charAt(i));
                i++;
                continue;
            }
            if (i + 2 < raw.length() && raw.charAt(i + 2) == '{') {
                final var close = raw.indexOf('}', i + 3);
                if (close < 0) {
                    throw invalid("invalid capture group name '" + raw + "'");
                }
                out.appendCodePoint(parseCodePoint(raw.substring(i + 3, close), raw));
                i = close + 1;
                continue;
            }
            if (i + 6 > raw.length()) {
                throw invalid("invalid capture group name '" + raw + "'");
            }
            out.append((char) parseCodePoint(raw.substring(i + 2, i + 6), raw));
            i += 6;
        }
        return out.toString();
    }

    protected static int parseCodePoint(String hex, String raw) {
        try {
            final var value = Integer.parseInt(hex, 16);
            if (value > Character.MAX_CODE_POINT) {
                throw invalid("invalid capture group name '" + raw + "'");
            }
            return value;
        } catch (NumberFormatException notHex) {
            throw invalid("invalid capture group name '" + raw + "'");
        }
    }

    protected static void validateGroupName(String name) {
        if (name.isEmpty()) {
            throw invalid("empty capture group name");
        }
        var i = 0;
        while (i < name.length()) {
            final var cp = name.codePointAt(i);
            final var ok = i == 0
                    ? Character.isUnicodeIdentifierStart(cp) || cp == '$' || cp == '_'
                    : Character.isUnicodeIdentifierPart(cp) || cp == '$' || cp == 0x200C || cp == 0x200D;
            if (!ok) {
                throw invalid("invalid capture group name '" + name + "'");
            }
            i += Character.charCount(cp);
        }
    }

    protected static void checkModifiers(String add, String remove) {
        for (var i = 0; i < add.length(); i++) {
            if (add.indexOf(add.charAt(i), i + 1) >= 0 || remove.indexOf(add.charAt(i)) >= 0) {
                throw invalid("invalid regular expression modifiers");
            }
        }
        for (var i = 0; i < remove.length(); i++) {
            if (remove.indexOf(remove.charAt(i), i + 1) >= 0) {
                throw invalid("invalid regular expression modifiers");
            }
        }
    }

    protected static long clampRepetition(String digits) {
        try {
            return Math.min(Long.parseLong(digits), MAX_REPETITION);
        } catch (NumberFormatException overflow) {
            return MAX_REPETITION;
        }
    }

    protected int readCharacterEscape(boolean inClass) {
        final var c = source.charAt(pos + 1);
        return switch (c) {
            case 'f' -> {
                pos += 2;
                yield '\f';
            }
            case 'n' -> {
                pos += 2;
                yield '\n';
            }
            case 'r' -> {
                pos += 2;
                yield '\r';
            }
            case 't' -> {
                pos += 2;
                yield '\t';
            }
            case 'v' -> {
                pos += 2;
                yield 0x0B;
            }
            case 'b' -> {
                if (!inClass) {
                    throw invalid("unexpected \\b");
                }
                pos += 2;
                yield 0x08;
            }
            case 'c' -> readControlEscape(inClass);
            case 'x' -> readHexEscape();
            case 'u' -> readUnicodeEscape();
            case '0' -> readNullEscape();
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readClassDigitEscape();
            case '-' -> {
                if (!inClass && unicode) {
                    throw invalid("invalid escape '\\-'");
                }
                pos += 2;
                yield '-';
            }
            default -> readIdentityEscape(c);
        };
    }

    protected int readIdentityEscape(char c) {
        if (unicode && SYNTAX_CHARACTERS.indexOf(c) < 0 && c != '/') {
            throw invalid("invalid escape '\\" + c + "'");
        }
        pos += 2;
        return c;
    }

    protected int readControlEscape(boolean inClass) {
        if (has(2) && Character.isLetter(source.charAt(pos + 2)) && source.charAt(pos + 2) < 0x80) {
            final var letter = source.charAt(pos + 2);
            pos += 3;
            return letter % 32;
        }
        if (unicode) {
            throw invalid("invalid control escape");
        }
        if (inClass && has(2) && (Character.isDigit(source.charAt(pos + 2)) || source.charAt(pos + 2) == '_')) {
            final var letter = source.charAt(pos + 2);
            pos += 3;
            return letter % 32;
        }
        pos += 1;
        return '\\';
    }

    protected int readHexEscape() {
        if (hexRun(pos + 2, 2) == 2) {
            final var value = Integer.parseInt(source.substring(pos + 2, pos + 4), 16);
            pos += 4;
            return value;
        }
        if (unicode) {
            throw invalid("invalid hexadecimal escape");
        }
        pos += 2;
        return 'x';
    }

    protected int readUnicodeEscape() {
        if (unicode && has(2) && source.charAt(pos + 2) == '{') {
            final var close = source.indexOf('}', pos + 3);
            if (close < 0 || close == pos + 3) {
                throw invalid("invalid unicode escape");
            }
            final var body = source.substring(pos + 3, close);
            final long value;
            try {
                value = Long.parseLong(body, 16);
            } catch (NumberFormatException notHex) {
                throw invalid("invalid unicode escape");
            }
            if (value > Character.MAX_CODE_POINT) {
                throw invalid("unicode escape out of range");
            }
            pos = close + 1;
            return (int) value;
        }
        if (hexRun(pos + 2, 4) == 4) {
            final var value = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
            pos += 6;
            if (unicode && Character.isHighSurrogate((char) value) && startsWith("\\u") && hexRun(pos + 2, 4) == 4) {
                final var low = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
                if (Character.isLowSurrogate((char) low)) {
                    pos += 6;
                    return Character.toCodePoint((char) value, (char) low);
                }
            }
            return value;
        }
        if (unicode) {
            throw invalid("invalid unicode escape");
        }
        pos += 2;
        return 'u';
    }

    protected int hexRun(int from, int wanted) {
        var count = 0;
        while (count < wanted && from + count < source.length()
                && Character.digit(source.charAt(from + count), 16) >= 0) {
            count++;
        }
        return count;
    }

    protected int readNullEscape() {
        if (has(2) && Character.isDigit(source.charAt(pos + 2))) {
            if (unicode) {
                throw invalid("invalid legacy octal escape");
            }
            return readOctalEscape();
        }
        pos += 2;
        return 0;
    }

    protected int readClassDigitEscape() {
        if (unicode) {
            throw invalid("invalid decimal escape");
        }
        if (source.charAt(pos + 1) >= '8') {
            final var literal = source.charAt(pos + 1);
            pos += 2;
            return literal;
        }
        return readOctalEscape();
    }

    protected int readOctalEscape() {
        var end = pos + 1;
        var value = 0;
        while (end < source.length() && end < pos + 4 && source.charAt(end) >= '0' && source.charAt(end) <= '7'
                && value * 8 + (source.charAt(end) - '0') <= 0xFF) {
            value = value * 8 + (source.charAt(end) - '0');
            end++;
        }
        pos = end;
        return value;
    }

    protected int readUnicodeCodePoint() {
        if (has(2) && source.charAt(pos + 2) == '{') {
            final var close = source.indexOf('}', pos + 3);
            if (close < 0 || close == pos + 3) {
                throw invalid("invalid unicode escape");
            }
            final var value = Integer.parseInt(source.substring(pos + 3, close), 16);
            if (value > Character.MAX_CODE_POINT) {
                throw invalid("unicode escape out of range");
            }
            pos = close + 1;
            return value;
        }
        if (hexRun(pos + 2, 4) != 4) {
            throw invalid("invalid unicode escape");
        }
        final var high = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
        pos += 6;
        if (Character.isHighSurrogate((char) high) && startsWith("\\u") && hexRun(pos + 2, 4) == 4) {
            final var low = Integer.parseInt(source.substring(pos + 2, pos + 6), 16);
            if (Character.isLowSurrogate((char) low)) {
                pos += 6;
                return Character.toCodePoint((char) high, (char) low);
            }
        }
        return high;
    }

    protected static ClassOperand combine(String operator, List<ClassOperand> operands) {
        return operator.isEmpty()
                ? operands.getFirst()
                : "&&".equals(operator) ? intersect(operands) : subtract(operands);
    }

    protected static ClassOperand intersect(List<ClassOperand> operands) {
        final var result = new ClassOperand();
        result.points = operands.getFirst().points;
        for (final var operand : operands.subList(1, operands.size())) {
            result.points = result.points.intersect(operand.points);
        }
        result.strings.addAll(operands.getFirst().strings);
        for (final var operand : operands.subList(1, operands.size())) {
            result.strings.retainAll(operand.strings);
        }
        return result;
    }

    protected static ClassOperand subtract(List<ClassOperand> operands) {
        final var result = new ClassOperand();
        result.points = operands.getFirst().points;
        for (final var operand : operands.subList(1, operands.size())) {
            result.points = result.points.subtract(operand.points);
        }
        result.strings.addAll(operands.getFirst().strings);
        for (final var operand : operands.subList(1, operands.size())) {
            result.strings.removeAll(operand.strings);
        }
        return result;
    }

    protected RxNode alternationOfStrings(List<String> strings, CodePointSet fallback) {
        final var ordered = new ArrayList<>(new LinkedHashSet<>(strings));
        ordered.sort((left, right) -> Integer.compare(right.codePointCount(0, right.length()),
                left.codePointCount(0, left.length())));
        final var branches = new ArrayList<RxNode>();
        for (final var alternative : ordered) {
            branches.add(new RxNode.Literal(alternative, ignoreCase, unicode));
        }
        if (!fallback.isEmpty()) {
            branches.add(new RxNode.CharClass(fallback));
        }
        return branches.size() == 1 ? branches.getFirst() : new RxNode.Alternation(branches);
    }

    protected int propertyBodyEnd(char kind) {
        if (!has(2) || source.charAt(pos + 2) != '{') {
            throw invalid("incomplete \\" + kind + "{} property");
        }
        final var close = source.indexOf('}', pos + 3);
        if (close < 0) {
            throw invalid("unterminated \\" + kind + "{ property");
        }
        return close;
    }
}
