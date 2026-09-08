package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.RESERVED_DOUBLE_PUNCTUATORS;
import static org.techhouse.simplejs.internal.regex.RegexTables.SET_SYNTAX_CHARACTERS;
import static org.techhouse.simplejs.internal.regex.RegexTables.invalid;

import java.util.ArrayList;

abstract class SetClassParser extends CharacterClassParser {
    protected SetClassParser(String source, String flags) {
        super(source, flags);
    }

    protected RxNode parseSetClass() {
        final var operand = parseSetOperand();
        return operand.strings.isEmpty()
                ? new RxNode.CharClass(operand.points)
                : alternationOfStrings(operand.strings, operand.points);
    }

    protected ClassOperand parseSetOperand() {
        pos++;
        final var negated = pos < source.length() && peek() == '^';
        if (negated) {
            pos++;
        }
        final var operands = new ArrayList<ClassOperand>();
        var operator = "";
        var current = new ClassOperand();
        while (pos < source.length() && peek() != ']') {
            if (startsWith("&&") || startsWith("--")) {
                if (current.isEmpty()) {
                    throw invalid("invalid set operation");
                }
                final var thisOp = source.substring(pos, pos + 2);
                if (!operator.isEmpty() && !operator.equals(thisOp)) {
                    throw invalid("cannot mix set operators in a character class");
                }
                operator = thisOp;
                operands.add(current);
                current = new ClassOperand();
                pos += 2;
                continue;
            }
            appendSetMember(current);
        }
        expect(']');
        if (!operator.isEmpty() && current.isEmpty()) {
            throw invalid("invalid set operation");
        }
        operands.add(current);
        return finish(negated, combine(operator, operands));
    }

    protected ClassOperand finish(boolean negated, ClassOperand combined) {
        if (negated && !combined.strings.isEmpty()) {
            throw invalid("a negated class may not contain strings");
        }
        final var widened = new ClassOperand();
        widened.points = foldedSet(combined.points);
        widened.strings.addAll(combined.strings);
        if (!negated) {
            return widened;
        }
        final var flipped = new ClassOperand();
        flipped.points = widened.points.negate();
        return flipped;
    }

    protected void appendSetMember(ClassOperand target) {
        final var c = peek();
        if (c == '[') {
            target.merge(parseSetOperand());
            return;
        }
        if (c == '\\' && has(1) && source.charAt(pos + 1) == 'q') {
            readStringAlternatives(target);
            return;
        }
        if (c == '\\' && has(1) && (source.charAt(pos + 1) == 'p' || source.charAt(pos + 1) == 'P')
                && stringProperty(target)) {
            return;
        }
        if (c == '\\') {
            final var atom = readClassAtom();
            appendSetRange(target, atom);
            return;
        }
        if (SET_SYNTAX_CHARACTERS.indexOf(c) >= 0) {
            throw invalid("'" + c + "' must be escaped in a unicodeSets character class");
        }
        if (has(1) && source.charAt(pos + 1) == c && RESERVED_DOUBLE_PUNCTUATORS.indexOf(c) >= 0) {
            throw invalid("reserved double punctuator '" + c + c + "'");
        }
        final var cp = source.codePointAt(pos);
        pos += Character.charCount(cp);
        appendSetRange(target, new ClassAtom(cp, null));
    }

    protected boolean stringProperty(ClassOperand target) {
        final var kind = source.charAt(pos + 1);
        final var body = source.substring(pos + 3, propertyBodyEnd(kind));
        if (!UnicodeProperty.StringProperties.has(body)) {
            return false;
        }
        if (kind == 'P') {
            throw invalid("property of strings '" + body + "' requires the v flag and may not be negated");
        }
        pos += 4 + body.length();
        for (final var sequence : UnicodeProperty.StringProperties.get(body)) {
            target.addString(sequence);
        }
        if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']' && !startsWith("--")) {
            throw invalid("invalid character class range");
        }
        return true;
    }

    protected void appendSetRange(ClassOperand target, ClassAtom low) {
        if (pos + 1 < source.length() && peek() == '-' && source.charAt(pos + 1) != ']' && !startsWith("--")) {
            pos++;
            final var high = readClassAtom();
            if (low.isSet() || high.isSet()) {
                throw invalid("invalid character class range");
            }
            if (low.codePoint() > high.codePoint()) {
                throw invalid("range out of order in character class");
            }
            target.addPoints(CodePointSet.of(low.codePoint(), high.codePoint()));
            return;
        }
        target.addPoints(low.isSet() ? low.asSet() : CodePointSet.ofChar(low.codePoint()));
    }

    protected void readStringAlternatives(ClassOperand target) {
        if (!has(2) || source.charAt(pos + 2) != '{') {
            throw invalid("\\q must be followed by '{'");
        }
        pos += 3;
        var current = new StringBuilder();
        while (pos < source.length() && peek() != '}') {
            if (peek() == '|') {
                target.addString(current.toString());
                current = new StringBuilder();
                pos++;
                continue;
            }
            current.appendCodePoint(readSetCharacter());
        }
        if (pos >= source.length()) {
            throw invalid("unterminated \\q{ string literal");
        }
        pos++;
        target.addString(current.toString());
    }

    protected int readSetCharacter() {
        if (peek() != '\\') {
            final var cp = source.codePointAt(pos);
            pos += Character.charCount(cp);
            return cp;
        }
        if (!has(1)) {
            throw invalid("\\ at end of pattern");
        }
        final var c = source.charAt(pos + 1);
        return switch (c) {
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
            case 'f' -> {
                pos += 2;
                yield '\f';
            }
            case 'v' -> {
                pos += 2;
                yield 0x0B;
            }
            case 'b' -> {
                pos += 2;
                yield 0x08;
            }
            case '0' -> {
                pos += 2;
                yield 0;
            }
            case 'x' -> {
                if (hexRun(pos + 2, 2) != 2) {
                    throw invalid("invalid hexadecimal escape");
                }
                final var value = Integer.parseInt(source.substring(pos + 2, pos + 4), 16);
                pos += 4;
                yield value;
            }
            case 'u' -> readUnicodeCodePoint();
            case 'c' -> {
                if (!has(2) || !Character.isLetter(source.charAt(pos + 2))) {
                    throw invalid("invalid control escape");
                }
                final var value = source.charAt(pos + 2) % 32;
                pos += 3;
                yield value;
            }
            default -> {
                if (SET_SYNTAX_CHARACTERS.indexOf(c) < 0 && RESERVED_DOUBLE_PUNCTUATORS.indexOf(c) < 0) {
                    throw invalid("invalid escape '\\" + c + "' in \\q{}");
                }
                pos += 2;
                yield c;
            }
        };
    }
}
