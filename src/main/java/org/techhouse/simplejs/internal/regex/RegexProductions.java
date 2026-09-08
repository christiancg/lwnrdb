package org.techhouse.simplejs.internal.regex;

import static org.techhouse.simplejs.internal.regex.RegexTables.DIGITS;
import static org.techhouse.simplejs.internal.regex.RegexTables.LINE_TERMINATORS;
import static org.techhouse.simplejs.internal.regex.RegexTables.MODIFIER_FLAGS;
import static org.techhouse.simplejs.internal.regex.RegexTables.WHITESPACE;
import static org.techhouse.simplejs.internal.regex.RegexTables.WORD_CHARS;
import static org.techhouse.simplejs.internal.regex.RegexTables.invalid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

final class RegexProductions extends SetClassParser {

    RegexProductions(String source, String flags) {
        super(source, flags);
    }

    RegexProgram parse() {
        prescan();
        final var body = parseDisjunction();
        if (pos < source.length()) {
            throw invalid("unmatched ')'");
        }
        return new RegexProgram(body.node(), totalGroups, aliases, unicode);
    }

    private void prescan() {
        var depth = 0;
        var i = 0;
        var counter = 0;
        while (i < source.length()) {
            final var c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '[') {
                depth += unicodeSets || depth == 0 ? 1 : 0;
                i++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
                i++;
            } else if (depth == 0 && c == '(') {
                if (i + 1 >= source.length() || source.charAt(i + 1) != '?') {
                    counter++;
                    i++;
                } else if (source.startsWith("(?<", i) && i + 3 < source.length()
                        && "=!".indexOf(source.charAt(i + 3)) < 0) {
                    final var close = source.indexOf('>', i + 3);
                    if (close < 0) {
                        throw invalid("unterminated group name");
                    }
                    counter++;
                    final var name = decodeGroupName(source.substring(i + 3, close));
                    aliases.computeIfAbsent(name, _ -> new ArrayList<>()).add(counter);
                    i = close + 1;
                } else {
                    i += 2;
                }
            } else {
                i++;
            }
        }
        totalGroups = counter;
    }

    private Parsed parseDisjunction() {
        final var branches = new ArrayList<RxNode>();
        var first = parseAlternative();
        branches.add(first.node());
        final var names = new LinkedHashSet<>(first.names());
        while (pos < source.length() && peek() == '|') {
            pos++;
            final var next = parseAlternative();
            branches.add(next.node());
            names.addAll(next.names());
        }
        final var node = branches.size() == 1 ? branches.getFirst() : new RxNode.Alternation(branches);
        return new Parsed(node, names);
    }

    private Parsed parseAlternative() {
        final var terms = new ArrayList<RxNode>();
        final var names = new LinkedHashSet<String>();
        while (pos < source.length() && peek() != '|' && peek() != ')') {
            final var term = parseTerm();
            for (final var name : term.names()) {
                if (!names.add(name)) {
                    throw invalid("duplicate capture group name '" + name + "'");
                }
            }
            terms.add(term.node());
        }
        final var node = terms.size() == 1 ? terms.getFirst() : new RxNode.Sequence(terms);
        return new Parsed(node, names);
    }

    private Parsed parseTerm() {
        if (isAssertion()) {
            final var node = parseAssertion();
            rejectQuantifier();
            return new Parsed(node, Set.of());
        }
        if (startsWith("(?=") || startsWith("(?!")) {
            final var negate = source.charAt(pos + 2) == '!';
            pos += 3;
            final var body = parseDisjunction();
            expect(')');
            final var node = new RxNode.Lookaround(false, negate, body.node());
            if (unicode) {
                rejectQuantifier();
                return new Parsed(node, body.names());
            }
            return new Parsed(applyQuantifier(node), body.names());
        }
        if (startsWith("(?<=") || startsWith("(?<!")) {
            final var negate = source.charAt(pos + 3) == '!';
            pos += 4;
            final var body = parseDisjunction();
            expect(')');
            rejectQuantifier();
            return new Parsed(new RxNode.Lookaround(true, negate, body.node()), body.names());
        }
        final var atom = parseAtom();
        return new Parsed(applyQuantifier(atom.node()), atom.names());
    }

    private boolean isAssertion() {
        final var c = peek();
        return c == '^' || c == '$'
                || (c == '\\' && has(1) && (source.charAt(pos + 1) == 'b' || source.charAt(pos + 1) == 'B'));
    }

    private RxNode parseAssertion() {
        final var c = peek();
        if (c == '^') {
            pos++;
            return new RxNode.Assertion(
                    multiline ? RxNode.Assertion.Kind.LINE_START : RxNode.Assertion.Kind.INPUT_START);
        }
        if (c == '$') {
            pos++;
            return new RxNode.Assertion(multiline ? RxNode.Assertion.Kind.LINE_END : RxNode.Assertion.Kind.INPUT_END);
        }
        final var negated = source.charAt(pos + 1) == 'B';
        pos += 2;
        return new RxNode.WordBoundary(foldedSet(WORD_CHARS), negated);
    }

    private Parsed parseAtom() {
        final var c = peek();
        return switch (c) {
            case '.' -> {
                pos++;
                final var set = dotAll ? CodePointSet.ALL : LINE_TERMINATORS.negate();
                yield new Parsed(new RxNode.CharClass(set), Set.of());
            }
            case '\\' -> new Parsed(parseAtomEscape(), Set.of());
            case '(' -> parseGroup();
            case '[' -> new Parsed(unicodeSets ? parseSetClass() : new RxNode.CharClass(parseClass()), Set.of());
            case '*', '+', '?' -> throw invalid("nothing to repeat");
            case '{' -> {
                if (unicode || readQuantifierBraces(pos) > pos) {
                    throw invalid("nothing to repeat");
                }
                pos++;
                yield new Parsed(literalNode('{'), Set.of());
            }
            case ']', '}' -> {
                if (unicode) {
                    throw invalid("lone quantifier brackets");
                }
                pos++;
                yield new Parsed(literalNode(c), Set.of());
            }
            default -> {
                final var codePoint = source.codePointAt(pos);
                pos += Character.charCount(codePoint);
                yield new Parsed(literalNode(codePoint), Set.of());
            }
        };
    }

    private RxNode literalNode(int codePoint) {
        if (codePoint > 0xFFFF) {
            return new RxNode.Literal(new String(Character.toChars(codePoint)), ignoreCase, unicode);
        }
        return new RxNode.CharClass(foldedSet(CodePointSet.ofChar(codePoint)));
    }

    private Parsed parseGroup() {
        if (startsWith("(?:")) {
            pos += 3;
            final var body = parseDisjunction();
            expect(')');
            return body;
        }
        if (startsWith("(?<")) {
            return parseNamedGroup();
        }
        if (startsWith("(?")) {
            return parseModifierGroup();
        }
        pos++;
        final var number = ++groupCounter;
        final var body = parseDisjunction();
        expect(')');
        return new Parsed(new RxNode.Group(number, body.node()), body.names());
    }

    private Parsed parseNamedGroup() {
        final var close = source.indexOf('>', pos + 3);
        if (close < 0) {
            throw invalid("unterminated group name");
        }
        final var name = decodeGroupName(source.substring(pos + 3, close));
        validateGroupName(name);
        pos = close + 1;
        final var number = ++groupCounter;
        final var body = parseDisjunction();
        expect(')');
        final var declared = new LinkedHashSet<String>();
        declared.add(name);
        declared.addAll(body.names());
        return new Parsed(new RxNode.Group(number, body.node()), declared);
    }

    private Parsed parseModifierGroup() {
        var cursor = pos + 2;
        final var add = readModifiers(cursor);
        cursor += add.length();
        var remove = "";
        if (cursor < source.length() && source.charAt(cursor) == '-') {
            cursor++;
            remove = readModifiers(cursor);
            cursor += remove.length();
            if (remove.isEmpty() && add.isEmpty()) {
                throw invalid("invalid regular expression modifiers");
            }
        } else if (add.isEmpty()) {
            throw invalid("invalid group");
        }
        if (cursor >= source.length() || source.charAt(cursor) != ':') {
            throw invalid("invalid regular expression modifiers");
        }
        checkModifiers(add, remove);
        final var previousMultiline = multiline;
        final var previousDotAll = dotAll;
        final var previousIgnoreCase = ignoreCase;
        multiline = add.indexOf('m') >= 0 || (multiline && remove.indexOf('m') < 0);
        dotAll = add.indexOf('s') >= 0 || (dotAll && remove.indexOf('s') < 0);
        ignoreCase = add.indexOf('i') >= 0 || (ignoreCase && remove.indexOf('i') < 0);
        pos = cursor + 1;
        final var body = parseDisjunction();
        expect(')');
        multiline = previousMultiline;
        dotAll = previousDotAll;
        ignoreCase = previousIgnoreCase;
        return body;
    }

    private String readModifiers(int from) {
        var end = from;
        while (end < source.length() && MODIFIER_FLAGS.indexOf(source.charAt(end)) >= 0) {
            end++;
        }
        return source.substring(from, end);
    }

    private void rejectQuantifier() {
        if (pos < source.length()
                && (peek() == '*' || peek() == '+' || peek() == '?' || readQuantifierBraces(pos) > pos)) {
            throw invalid("nothing to repeat");
        }
    }

    private RxNode applyQuantifier(RxNode atom) {
        if (pos >= source.length()) {
            return atom;
        }
        final var c = peek();
        final int min;
        final int max;
        if (c == '*') {
            pos++;
            min = 0;
            max = RxNode.Quantifier.UNBOUNDED;
        } else if (c == '+') {
            pos++;
            min = 1;
            max = RxNode.Quantifier.UNBOUNDED;
        } else if (c == '?') {
            pos++;
            min = 0;
            max = 1;
        } else if (c == '{') {
            final var end = readQuantifierBraces(pos);
            if (end == pos) {
                return atom;
            }
            final var bounds = readBraces(source.substring(pos + 1, end - 1));
            min = bounds[0];
            max = bounds[1];
            pos = end;
        } else {
            return atom;
        }
        var greedy = true;
        if (pos < source.length() && peek() == '?') {
            greedy = false;
            pos++;
        }
        return new RxNode.Quantifier(atom, min, max, greedy);
    }

    private int[] readBraces(String body) {
        final var comma = body.indexOf(',');
        final var min = clampRepetition(comma < 0 ? body : body.substring(0, comma));
        if (comma < 0) {
            return new int[]{(int) min, (int) min};
        }
        if (comma == body.length() - 1) {
            return new int[]{(int) min, RxNode.Quantifier.UNBOUNDED};
        }
        final var max = clampRepetition(body.substring(comma + 1));
        if (min > max) {
            throw invalid("numbers out of order in {} quantifier");
        }
        return new int[]{(int) min, (int) max};
    }

    private int readQuantifierBraces(int from) {
        if (from >= source.length() || source.charAt(from) != '{') {
            return from;
        }
        var i = from + 1;
        final var digitsStart = i;
        while (i < source.length() && Character.isDigit(source.charAt(i))) {
            i++;
        }
        if (i == digitsStart) {
            return from;
        }
        if (i < source.length() && source.charAt(i) == ',') {
            i++;
            final var secondStart = i;
            while (i < source.length() && Character.isDigit(source.charAt(i))) {
                i++;
            }
            if (i == secondStart && (i >= source.length() || source.charAt(i) != '}')) {
                return from;
            }
        }
        return i < source.length() && source.charAt(i) == '}' ? i + 1 : from;
    }

    private RxNode parseAtomEscape() {
        if (!has(1)) {
            throw invalid("\\ at end of pattern");
        }
        final var c = source.charAt(pos + 1);
        return switch (c) {
            case 'd' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(DIGITS));
            }
            case 'D' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(DIGITS).negate());
            }
            case 'w' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(WORD_CHARS));
            }
            case 'W' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(WORD_CHARS).negate());
            }
            case 's' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(WHITESPACE));
            }
            case 'S' -> {
                pos += 2;
                yield new RxNode.CharClass(foldedSet(WHITESPACE).negate());
            }
            case 'k' -> parseNamedBackreference();
            case 'p', 'P' -> parsePropertyEscape(c == 'P');
            case '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumericAtomEscape();
            default -> literalNode(readCharacterEscape(false));
        };
    }

    private RxNode parseNumericAtomEscape() {
        var end = pos + 1;
        while (end < source.length() && Character.isDigit(source.charAt(end))) {
            end++;
        }
        final var digits = source.substring(pos + 1, end);
        final var value = digits.length() > 9 ? Long.MAX_VALUE : Long.parseLong(digits);
        if (value <= totalGroups) {
            pos = end;
            return new RxNode.Backreference(new int[]{(int) value}, ignoreCase, unicode);
        }
        if (unicode) {
            throw invalid("invalid decimal escape");
        }
        if (source.charAt(pos + 1) >= '8') {
            final var literal = source.charAt(pos + 1);
            pos += 2;
            return literalNode(literal);
        }
        return literalNode(readOctalEscape());
    }

    private RxNode parseNamedBackreference() {
        if (!has(2) || source.charAt(pos + 2) != '<' || (aliases.isEmpty() && !unicode)) {
            if (unicode || !aliases.isEmpty()) {
                throw invalid("invalid named reference");
            }
            pos += 2;
            return literalNode('k');
        }
        final var close = source.indexOf('>', pos + 3);
        if (close < 0) {
            throw invalid("invalid named reference");
        }
        final var name = decodeGroupName(source.substring(pos + 3, close));
        final var targets = aliases.get(name);
        if (targets == null) {
            throw invalid("invalid named reference to '" + name + "'");
        }
        pos = close + 1;
        return new RxNode.Backreference(targets.stream().mapToInt(Integer::intValue).toArray(), ignoreCase, unicode);
    }

    private RxNode parsePropertyEscape(boolean negated) {
        final var kind = negated ? 'P' : 'p';
        if (!unicode) {
            pos += 2;
            return literalNode(kind);
        }
        final var close = propertyBodyEnd(kind);
        final var body = source.substring(pos + 3, close);
        pos = close + 1;
        if (UnicodeProperty.StringProperties.has(body)) {
            if (!unicodeSets || negated) {
                throw invalid("property of strings '" + body + "' requires the v flag and may not be negated");
            }
            return alternationOfStrings(UnicodeProperty.StringProperties.get(body), CodePointSet.EMPTY);
        }
        final var raw = UnicodeProperty.rawSet(UnicodeProperty.translate(body));
        final var afterNegation = negated ? raw.negate() : raw;
        return new RxNode.CharClass(foldedSet(afterNegation));
    }
}
