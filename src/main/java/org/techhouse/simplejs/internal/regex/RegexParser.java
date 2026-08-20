package org.techhouse.simplejs.internal.regex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;

/**
 * Recursive-descent parser/compiler from an ECMA-262 {@code Pattern} straight to the {@link RxNode}
 * AST {@link RegexMatcher} executes - no intermediate {@code java.util.regex} syntax. Also the
 * validator: every early error the grammar requires (Annex B leniency, {@code u}/{@code v}
 * strictness, modifier groups, {@code v}-mode set notation) is raised here.
 *
 * <p>Case-insensitivity is resolved once, at compile time: every character class this parser builds
 * is widened via {@link CaseFold#widen} to include every case-equivalence partner of a member it
 * already has, so {@link RegexMatcher} never needs to fold a candidate character - a plain
 * {@code contains} test is always enough (backreferences are the one runtime exception; see
 * {@link RegexMatcher}).
 */
public final class RegexParser {
    private static final String VALID_FLAGS = "dgimsuvy";
    private static final String SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|";
    private static final String SET_SYNTAX_CHARACTERS = "()[]{}/-\\|";
    private static final String RESERVED_DOUBLE_PUNCTUATORS = "&!#$%*+,.:;<=>?@^`~";
    private static final String MODIFIER_FLAGS = "ims";
    private static final int MAX_REPETITION = 0x7FFF_FFFE;

    private static final CodePointSet LINE_TERMINATORS = new CodePointSet.Builder().addChar('\n').addChar('\r')
            .addChar('\u2028').addChar('\u2029').build();
    private static final CodePointSet WHITESPACE = buildWhitespace();
    private static final CodePointSet DIGITS = CodePointSet.of('0', '9');
    private static final CodePointSet WORD_CHARS = new CodePointSet.Builder().addRange('a', 'z').addRange('A', 'Z')
            .addRange('0', '9').addChar('_').build();

    private RegexParser() {
    }

    private static CodePointSet buildWhitespace() {
        final var builder = new CodePointSet.Builder();
        for (final var cp : new int[]{' ', '\t', '\n', 0x0B, '\f', '\r', 0x00a0, 0x1680, 0x2028, 0x2029, 0x202f, 0x205f,
                0x3000, 0xfeff}) {
            builder.addChar(cp);
        }
        builder.addRange(0x2000, 0x200a);
        return builder.build();
    }

    public static RegexProgram compile(String source, String flags) {
        validateFlags(flags);
        final var parser = new Parser(source, flags);
        return parser.parse();
    }

    public static Map<String, List<Integer>> aliasesOf(RegexProgram program) {
        return program.groupAliases();
    }

    private static void validateFlags(String flags) {
        for (var i = 0; i < flags.length(); i++) {
            final var flag = flags.charAt(i);
            if (VALID_FLAGS.indexOf(flag) < 0 || flags.indexOf(flag, i + 1) >= 0) {
                throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
            }
        }
        if (flags.indexOf('u') >= 0 && flags.indexOf('v') >= 0) {
            throw new SyntaxErrorException("Invalid regular expression flags: " + flags);
        }
    }

    private static SyntaxErrorException invalid(String detail) {
        return new SyntaxErrorException("Invalid regular expression: " + detail);
    }

    private record Parsed(RxNode node, Set<String> names) {
    }

    // A v-mode class operand: the code points it accepts, plus any multi-code-point alternatives
    // contributed by \q{} or a property of strings.
    private static final class ClassOperand {
        private CodePointSet points = CodePointSet.EMPTY;
        private final List<String> strings = new ArrayList<>();

        private boolean isEmpty() {
            return points.isEmpty() && strings.isEmpty();
        }

        private void addPoints(CodePointSet set) {
            points = points.union(set);
        }

        private void addString(String text) {
            if (text.codePointCount(0, text.length()) == 1) {
                addPoints(CodePointSet.ofChar(text.codePointAt(0)));
            } else {
                strings.add(text);
            }
        }

        private void merge(ClassOperand other) {
            addPoints(other.points);
            strings.addAll(other.strings);
        }
    }

    private static final class Parser {
        private final String source;
        private final boolean unicode;
        private final boolean unicodeSets;
        private boolean ignoreCase;
        private boolean multiline;
        private boolean dotAll;
        private final Map<String, List<Integer>> aliases = new LinkedHashMap<>();
        private int totalGroups;
        private int groupCounter;
        private int pos;

        Parser(String source, String flags) {
            this.source = source;
            this.unicodeSets = flags.indexOf('v') >= 0;
            this.unicode = flags.indexOf('u') >= 0 || unicodeSets;
            this.ignoreCase = flags.indexOf('i') >= 0;
            this.multiline = flags.indexOf('m') >= 0;
            this.dotAll = flags.indexOf('s') >= 0;
        }

        RegexProgram parse() {
            prescan();
            final var body = parseDisjunction();
            if (pos < source.length()) {
                throw invalid("unmatched ')'");
            }
            return new RegexProgram(body.node(), totalGroups, aliases, unicode);
        }

        // Capturing groups are numbered by their opening paren's textual order, matching the order
        // the real parse below will assign them in, so a backreference may point forward to a group
        // not yet parsed.
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

        private char peek() {
            return source.charAt(pos);
        }

        private boolean has(int offset) {
            return pos + offset < source.length();
        }

        private boolean startsWith(String prefix) {
            return source.startsWith(prefix, pos);
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
                return new RxNode.Assertion(
                        multiline ? RxNode.Assertion.Kind.LINE_END : RxNode.Assertion.Kind.INPUT_END);
            }
            final var negated = source.charAt(pos + 1) == 'B';
            pos += 2;
            return new RxNode.WordBoundary(foldedSet(WORD_CHARS), negated);
        }

        private CodePointSet foldedSet(CodePointSet raw) {
            return ignoreCase ? CaseFold.widen(raw, unicode) : raw;
        }

        private void expect(char expected) {
            if (pos >= source.length() || peek() != expected) {
                throw invalid("expected '" + expected + "'");
            }
            pos++;
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

        // Without u/v, a literal atom matches one source *character* (one UTF-16 code unit) at a
        // time, so an astral PatternCharacter is really two of them in sequence - a Literal (which
        // compares raw UTF-16 content directly, never combining surrogates) gets this right in
        // every mode, unlike a CharClass testing a single combined code point that a bare code unit
        // could never equal.
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

        // A GroupName is an IdentifierName, so it may spell its code points as a unicode escape; both
        // the declaration and every backreference to it must agree on the decoded form.
        private static String decodeGroupName(String raw) {
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

        private static int parseCodePoint(String hex, String raw) {
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

        private static void validateGroupName(String name) {
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

        // Modifier groups: (?ims-ims: ... ). Each set must be free of repeats, the two must be
        // disjoint, at least one must be non-empty, and the colon is mandatory. The modifiers are
        // purely a compile-time scoping of ignoreCase/multiline/dotAll: no runtime node is needed.
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

        private static void checkModifiers(String add, String remove) {
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

        private static long clampRepetition(String digits) {
            try {
                return Math.min(Long.parseLong(digits), MAX_REPETITION);
            } catch (NumberFormatException overflow) {
                return MAX_REPETITION;
            }
        }

        // Returns the index just past a well-formed {n} / {n,} / {n,m}, or `from` when the brace does
        // not open a quantifier at all (Annex B then treats it as a literal).
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

        // A numeric escape is a backreference when its full digit run names a group that exists
        // (however far forward); otherwise (u mode: always an error; Annex B: an octal/legacy escape)
        // it falls back to a literal, exactly like inside a character class.
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
            return new RxNode.Backreference(targets.stream().mapToInt(Integer::intValue).toArray(), ignoreCase,
                    unicode);
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

        // Strings longest-first so the alternation prefers the longest alternative, as a character
        // class would; a fallback CodePointSet.EMPTY omits the trailing CharClass branch entirely.
        private RxNode alternationOfStrings(List<String> strings, CodePointSet fallback) {
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

        private int propertyBodyEnd(char kind) {
            if (!has(2) || source.charAt(pos + 2) != '{') {
                throw invalid("incomplete \\" + kind + "{} property");
            }
            final var close = source.indexOf('}', pos + 3);
            if (close < 0) {
                throw invalid("unterminated \\" + kind + "{ property");
            }
            return close;
        }

        // Shared by the atom and character-class paths: decodes one escape sequence starting at the
        // backslash into its literal code point.
        private int readCharacterEscape(boolean inClass) {
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
                // Only reached inClass: an atom-position digit is intercepted by
                // parseNumericAtomEscape before readCharacterEscape is ever called.
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

        private int readIdentityEscape(char c) {
            if (unicode && SYNTAX_CHARACTERS.indexOf(c) < 0 && c != '/') {
                throw invalid("invalid escape '\\" + c + "'");
            }
            pos += 2;
            return c;
        }

        // The fallback (an invalid control escape kept as Annex B IdentityEscape) consumes only the
        // backslash, leaving 'c' to be re-scanned as its own literal on the next call - simpler than
        // trying to return two code points from one escape.
        private int readControlEscape(boolean inClass) {
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

        private int readHexEscape() {
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

        private int readUnicodeEscape() {
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
                if (unicode && Character.isHighSurrogate((char) value) && startsWith("\\u")
                        && hexRun(pos + 2, 4) == 4) {
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

        private int hexRun(int from, int wanted) {
            var count = 0;
            while (count < wanted && from + count < source.length()
                    && Character.digit(source.charAt(from + count), 16) >= 0) {
                count++;
            }
            return count;
        }

        private int readNullEscape() {
            if (has(2) && Character.isDigit(source.charAt(pos + 2))) {
                if (unicode) {
                    throw invalid("invalid legacy octal escape");
                }
                return readOctalEscape();
            }
            pos += 2;
            return 0;
        }

        // Reached only inClass: digits 1-9 there are always an Annex B octal/legacy digit escape,
        // never a backreference (parseNumericAtomEscape handles the atom position separately).
        private int readClassDigitEscape() {
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

        private int readOctalEscape() {
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

        // Case folding widens the class's own content *before* a leading `^` negates it (matching
        // ECMA-262 CharacterSetMatcher: invert is a boolean applied to the match test after
        // Canonicalize, not baked into the CharSet first) - so `[^o]` under ignoreCase excludes
        // "O" too, rather than only excluding "o" and then (wrongly) widening the excluded set back
        // open.
        private CodePointSet parseClass() {
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

        private void appendClassRange(ClassOperand operand) {
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

        private record ClassAtom(int codePoint, CodePointSet asSet) {
            boolean isSet() {
                return asSet != null;
            }
        }

        private ClassAtom readClassAtom() {
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

        private ClassAtom readClassProperty() {
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

        // Strings longest-first so the alternation prefers the longest alternative, as a character
        // class would; an operand with no string alternatives (the overwhelming common case) stays a
        // plain CharClass instead of a spurious one-branch Alternation.
        private RxNode parseSetClass() {
            final var operand = parseSetOperand();
            return operand.strings.isEmpty()
                    ? new RxNode.CharClass(operand.points)
                    : alternationOfStrings(operand.strings, operand.points);
        }

        private ClassOperand parseSetOperand() {
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

        // Case folding widens the combined operand *before* a leading `^` negates it - see
        // parseClass's note on CharacterSetMatcher order for why.
        private ClassOperand finish(boolean negated, ClassOperand combined) {
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

        private void appendSetMember(ClassOperand target) {
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

        private boolean stringProperty(ClassOperand target) {
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

        private void appendSetRange(ClassOperand target, ClassAtom low) {
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

        // The alternatives are ClassSetCharacters, so `\q{9️⃣}` is however many code
        // points it decodes to, not however many source characters it is spelled with.
        private void readStringAlternatives(ClassOperand target) {
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

        private int readSetCharacter() {
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

        private int readUnicodeCodePoint() {
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

        private static ClassOperand combine(String operator, List<ClassOperand> operands) {
            return operator.isEmpty()
                    ? operands.getFirst()
                    : "&&".equals(operator) ? intersect(operands) : subtract(operands);
        }

        private static ClassOperand intersect(List<ClassOperand> operands) {
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

        private static ClassOperand subtract(List<ClassOperand> operands) {
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
    }
}
