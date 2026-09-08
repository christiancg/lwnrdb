package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.DIGITS;
import static org.techhouse.simplejs.internal.regex.RegexTables.WHITESPACE;
import static org.techhouse.simplejs.internal.regex.RegexTables.WORD_CHARS;
import static org.techhouse.simplejs.internal.regex.RegexTables.invalid;

abstract class CharacterClassParser extends EscapeReader {
    protected CharacterClassParser(String source, String flags) {
        super(source, flags);
    }

    protected CodePointSet parseClass() {
        pos++;
        final var negated = pos < source.length() && peek() == '^';
        if (negated) {
            pos++;
        }
        final var operand = new ClassOperand();
        while (pos < source.length() && peek() != ']') {
            appendClassRange(operand);
        }
        expect(']');
        final var widened = foldedSet(operand.points);
        return negated ? widened.negate() : widened;
    }

    protected void appendClassRange(ClassOperand operand) {
        final var low = readClassAtom();
        if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']') {
            pos++;
            final var high = readClassAtom();
            if (low.isSet() || high.isSet()) {
                if (unicode) {
                    throw invalid("invalid character class range");
                }
                operand.addPoints(CodePointSet.ofChar('-'));
                operand.addPoints(low.isSet() ? low.asSet() : CodePointSet.ofChar(low.codePoint()));
                operand.addPoints(high.isSet() ? high.asSet() : CodePointSet.ofChar(high.codePoint()));
                return;
            }
            if (low.codePoint() > high.codePoint()) {
                throw invalid("range out of order in character class");
            }
            operand.addPoints(CodePointSet.of(low.codePoint(), high.codePoint()));
            return;
        }
        operand.addPoints(low.isSet() ? low.asSet() : CodePointSet.ofChar(low.codePoint()));
    }

    protected record ClassAtom(int codePoint, CodePointSet asSet) {
        boolean isSet() {
            return asSet != null;
        }
    }

    protected ClassAtom readClassAtom() {
        final var c = peek();
        if (c != '\\') {
            final var cp = source.codePointAt(pos);
            pos += Character.charCount(cp);
            return new ClassAtom(cp, null);
        }
        if (!has(1)) {
            throw invalid("\\ at end of pattern");
        }
        return switch (source.charAt(pos + 1)) {
            case 'd' -> {
                pos += 2;
                yield new ClassAtom(0, DIGITS);
            }
            case 'D' -> {
                pos += 2;
                yield new ClassAtom(0, foldedSet(DIGITS).negate());
            }
            case 'w' -> {
                pos += 2;
                yield new ClassAtom(0, WORD_CHARS);
            }
            case 'W' -> {
                pos += 2;
                yield new ClassAtom(0, foldedSet(WORD_CHARS).negate());
            }
            case 's' -> {
                pos += 2;
                yield new ClassAtom(0, WHITESPACE);
            }
            case 'S' -> {
                pos += 2;
                yield new ClassAtom(0, foldedSet(WHITESPACE).negate());
            }
            case 'p', 'P' -> readClassProperty();
            default -> new ClassAtom(readCharacterEscape(true), null);
        };
    }

    protected ClassAtom readClassProperty() {
        final var kind = source.charAt(pos + 1);
        if (!unicode) {
            pos += 2;
            return new ClassAtom(kind, null);
        }
        final var close = propertyBodyEnd(kind);
        final var body = source.substring(pos + 3, close);
        if (UnicodeProperty.StringProperties.has(body)) {
            throw invalid("property of strings '" + body + "' requires the v flag and may not be negated");
        }
        final var raw = UnicodeProperty.rawSet(UnicodeProperty.translate(body));
        pos = close + 1;
        return new ClassAtom(0, kind == 'P' ? raw.negate() : raw);
    }
}
